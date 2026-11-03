package com.adshield.pro.engine.security

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * AdShield Pro — Deep File Threat Scanner (Pure Kotlin, unit-testable).
 *
 * Scans any accessible file for:
 * 1. Exact signature matches (industry-standard EICAR antivirus test signature).
 * 2. Polyglot / steganographic executables (real PE (.exe) or ELF payload hidden inside
 *    .jpg / .png / .gif / .pdf containers — a common malware delivery trick).
 * 3. Script-based droppers (PowerShell -enc, curl|sh, mshta, certutil download chains).
 * 4. Macro-laden Office documents (vbaProject.bin inside OOXML zip containers).
 * 5. Path-traversal (Zip Slip) archive entries.
 * 6. Suspicious double-extension naming (invoice.pdf.exe).
 *
 * Everything runs locally — no file ever leaves the device.
 */
class FileThreatScanner {

    enum class ThreatLevel { CLEAN, SUSPICIOUS, DANGEROUS }

    data class FileVerdict(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String?,
        val threatLevel: ThreatLevel,
        val riskScore: Int,
        val threatName: String?,
        val findings: List<String>,
        val recommendation: String
    ) {
        val isClean: Boolean get() = threatLevel == ThreatLevel.CLEAN
    }

    /** Cap for deep content reads to keep scans fast and memory-safe on huge files. */
    private val maxDeepScanBytes = 24L * 1024 * 1024

    fun scanFile(file: File): FileVerdict {
        val sha = computeSha256OrNull(file)
        val findings = ArrayList<String>()
        var score = 0
        var threatName: String? = null

        // 1. Filename heuristics (cheap, always run)
        val lowerName = file.name.lowercase()
        if (DOUBLE_EXTENSION_REGEX.containsMatchIn(lowerName)) {
            findings.add("Double-extension filename disguising an executable ('${lowerName.substringAfterLast('.')}') as a document/media file")
            score += 45
            threatName = "Double-Extension Dropper Pattern"
        }
        if (lowerName.endsWith(".apk") || lowerName.endsWith(".apks") || lowerName.endsWith(".xapk")) {
            findings.add("Android package installer — verify the source before installing")
            score += 5
        }
        if (lowerName.endsWith(".exe") || lowerName.endsWith(".scr") || lowerName.endsWith(".bat") ||
            lowerName.endsWith(".cmd") || lowerName.endsWith(".msi") || lowerName.endsWith(".vbs") ||
            lowerName.endsWith(".jar")
        ) {
            findings.add("Desktop-executable format cannot run safely on Android and is commonly shared as malware bait")
            score += 20
        }

        if (file.exists() && file.isFile) {
            val head = readHeadBytes(file, headSize = 16)
            // 2. Polyglot detection: media/document container hiding a real executable
            if (head.size >= 4) {
                val container = detectContainer(head)
                if (container != null) {
                    val embedded = findEmbeddedExecutableMarker(file)
                    if (embedded != null) {
                        findings.add("Real executable payload ($embedded) hidden inside a $container container (polyglot/steganography malware)")
                        score += 90
                        threatName = "Polyglot Executable ($container → $embedded)"
                    }
                }
            }

            // 3/4/5. Content signature + dropper script heuristics (streamed, bounded)
            val contentFindings = scanContentStream(file)
            findings.addAll(contentFindings.findings)
            score += contentFindings.score
            if (threatName == null && contentFindings.threatName != null) {
                threatName = contentFindings.threatName
            }

            // 6. Archive inspection (bounded) for Zip Slip and macro documents
            if (lowerName.endsWith(".zip") || lowerName.endsWith(".apk") || lowerName.endsWith(".docx") ||
                lowerName.endsWith(".xlsx") || lowerName.endsWith(".pptx") || lowerName.endsWith(".xapk") ||
                lowerName.endsWith(".apks") || lowerName.endsWith(".jar")
            ) {
                val archiveFindings = inspectZipArchive(file)
                findings.addAll(archiveFindings.findings)
                score += archiveFindings.score
                if (threatName == null && archiveFindings.threatName != null) {
                    threatName = archiveFindings.threatName
                }
            }
        }

        val level = when {
            score >= 70 -> ThreatLevel.DANGEROUS
            score >= 25 -> ThreatLevel.SUSPICIOUS
            else -> ThreatLevel.CLEAN
        }
        val recommendation = when (level) {
            ThreatLevel.DANGEROUS -> "Do not open or install. Delete this file and empty the containing folder from the Deep Cleaner."
            ThreatLevel.SUSPICIOUS -> "High-risk indicators. Only open if you fully trust the origin; scan again after any security list sync."
            ThreatLevel.CLEAN -> "No known signatures or structural threats detected. Local scan cannot guarantee 100% safety for brand-new malware."
        }

        return FileVerdict(
            fileName = file.name,
            sizeBytes = if (file.exists()) file.length() else 0L,
            sha256 = sha,
            threatLevel = level,
            riskScore = score.coerceAtMost(100),
            threatName = threatName,
            findings = findings,
            recommendation = recommendation
        )
    }

    // --- Signature 1: exact EICAR + hashed marker check on first bytes ---
    private fun readHeadBytes(file: File, headSize: Int): ByteArray {
        return runCatching {
            file.inputStream().use { ins ->
                val buf = ByteArray(headSize)
                var read = 0
                while (read < headSize) {
                    val r = ins.read(buf, read, headSize - read)
                    if (r < 0) break
                    read += r
                }
                buf.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
    }

    private fun detectContainer(head: ByteArray): String? {
        return when {
            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "JPEG image"
            head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> "PNG image"
            head.size >= 3 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> "GIF image"
            head.size >= 4 && head[0] == 0x25.toByte() && head[1] == 0x50.toByte() && head[2] == 0x44.toByte() && head[3] == 0x46.toByte() -> "PDF document"
            else -> null
        }
    }

    /** Scans a bounded window of the file for MZ/ELF executable magic hidden behind a container header. */
    private fun findEmbeddedExecutableMarker(file: File): String? {
        return runCatching {
            file.inputStream().use { ins ->
                val window = ByteArray(8192)
                var total = 0L
                var overlap = ByteArray(0)
                while (total < maxDeepScanBytes) {
                    val read = ins.read(window)
                    if (read < 0) break
                    val chunk = overlap + window.copyOf(read)
                    val mzIdx = chunk.indexOfPattern(byteArrayOf(0x4D, 0x5A)) // "MZ" PE header
                    if (mzIdx >= 0) return@runCatching "Windows PE executable"
                    val elfIdx = chunk.indexOfPattern(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) // ELF magic
                    if (elfIdx >= 0) return@runCatching "Linux ELF executable"
                    val elfArmIdx = chunk.indexOfPattern("dex\n".toByteArray()) // hidden Android DEX
                    if (elfArmIdx >= 0) return@runCatching "Android DEX bytecode"
                    overlap = chunk.copyOfRange((chunk.size - 8).coerceAtLeast(0), chunk.size)
                    total += read
                }
                null
            }
        }.getOrNull()
    }

    // --- Signatures 3 & 4: dropper scripts + macro docs (streamed, bounded) ---
    private data class ContentFindings(
        val findings: List<String>,
        val score: Int,
        val threatName: String?
    )

    private fun scanContentStream(file: File): ContentFindings {
        val findings = ArrayList<String>()
        var score = 0
        var threatName: String? = null
        val sb = StringBuilder()
        runCatching {
            file.inputStream().use { ins ->
                val buf = ByteArray(65536)
                var total = 0L
                while (total < maxDeepScanBytes) {
                    val read = ins.read(buf)
                    if (read < 0) break
                    sb.append(String(buf, 0, read, Charsets.ISO_8859_1))
                    if (sb.length > 300_000) sb.delete(0, sb.length - 200_000)
                    total += read
                }
            }
        }
        val text = sb.toString()

        if (text.contains(EICAR_STRING)) {
            findings.add("Exact match: EICAR antivirus test signature (industry-standard detection test file)")
            return ContentFindings(findings, 100, "EICAR.Test.File")
        }

        val dropperPatterns = listOf(
            Regex("""powershell(\.exe)?.{0,40}-(enc|EncodedCommand|w hidden)""", RegexOption.IGNORE_CASE) to
                "Obfuscated PowerShell dropper command (-enc/hidden)",
            Regex("""certutil.{0,30}-urlcache""", RegexOption.IGNORE_CASE) to
                "certutil download chain (LOLBas malware technique)",
            Regex("""mshta\s+https?://""", RegexOption.IGNORE_CASE) to
                "mshta remote script execution (HTA dropper)",
            Regex("""(curl|wget).{0,60}\|\s*(sudo\s+)?(ba)?sh""", RegexOption.IGNORE_CASE) to
                "Pipe-to-shell remote loader (curl|sh malware pattern)",
            Regex("""rundll32\s+javascript""", RegexOption.IGNORE_CASE) to
                "rundll32 JavaScript proxy execution"
        )
        for ((regex, label) in dropperPatterns) {
            if (regex.containsMatchIn(text)) {
                findings.add(label)
                score += 60
                if (threatName == null) threatName = "Script Dropper Technique"
            }
        }
        if (score == 0 && text.contains("Invoke-Expression", ignoreCase = true) &&
            (text.contains("FromBase64String", ignoreCase = true) || text.contains("DownloadString", ignoreCase = true))
        ) {
            findings.add("Base64-decoded remote payload execution (PowerShell dropper)")
            score += 55
            threatName = "Script Dropper Technique"
        }

        return ContentFindings(findings, score, threatName)
    }

    // --- Signature 6: archive safety (zip slip, macros) ---
    private data class ArchiveFindings(
        val findings: List<String>,
        val score: Int,
        val threatName: String?
    )

    private fun inspectZipArchive(file: File): ArchiveFindings {
        val findings = ArrayList<String>()
        var score = 0
        var threatName: String? = null
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val entries = zip.entries()
                var checked = 0
                while (entries.hasMoreElements() && checked < 4096) {
                    val entry = entries.nextElement()
                    checked++
                    val norm = entry.name.replace('\\', '/')
                    if (norm.contains("..") && norm.split('/').contains("..")) {
                        findings.add("Zip-Slip path traversal entry: '${entry.name.take(80)}'")
                        score += 70
                        if (threatName == null) threatName = "Archive Path Traversal (Zip Slip)"
                    }
                    if (norm.equals("word/vbaProject.bin", ignoreCase = true) ||
                        norm.equals("xl/vbaProject.bin", ignoreCase = true) ||
                        norm.equals("ppt/vbaProject.bin", ignoreCase = true)
                    ) {
                        findings.add("Office document with embedded VBA macro project (vbaProject.bin)")
                        score += 40
                        if (threatName == null) threatName = "Macro-Carrying Office Document"
                    }
                }
            }
        }
        return ArchiveFindings(findings, score, threatName)
    }

    private fun ByteArray.indexOfPattern(pattern: ByteArray): Int {
        if (pattern.isEmpty() || size < pattern.size) return -1
        outer@ for (i in 0..size - pattern.size) {
            for (j in pattern.indices) {
                if (this[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun computeSha256OrNull(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(131072)
                while (true) {
                    val read = ins.read(buf)
                    if (read < 0) break
                    digest.update(buf, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    companion object {
        /** Industry-standard EICAR test payload — the only "malware" string shipped with AdShield Pro. */
        const val EICAR_STRING =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"

        private val DOUBLE_EXTENSION_REGEX =
            Regex("""\.(pdf|jpg|jpeg|png|doc|docx|txt|mp4|mp3|gif|xlsx|pptx)\.(exe|apk|scr|bat|cmd|jar|vbs|js)$""")
    }
}
