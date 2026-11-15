package com.adshield.pro.data.telegram

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** App-private paths handed to TDLib's setTdlibParameters. */
data class TelegramTdlibPaths(val databaseDirectory: String, val filesDirectory: String)

/** A SAF document copied into app-private storage so TDLib can read a stable local path. */
data class TelegramStagedFile(
    val path: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String
)

/**
 * The only Android file-system seam used by the transfer engine. URIs are passed as strings so
 * the engine and its JVM tests never touch android.net.Uri.
 */
interface TelegramFileIo {
    fun pathsFor(accountDir: String): TelegramTdlibPaths
    fun exists(path: String): Boolean
    suspend fun stage(sourceUri: String, name: String, mimeType: String): TelegramStagedFile
    suspend fun writeToDestination(localPath: String, destinationUri: String): TelegramHashResult
    suspend fun delete(path: String)
    suspend fun wipe()
}

@Singleton
class AndroidTelegramFileIo @Inject constructor(
    @ApplicationContext private val context: Context
) : TelegramFileIo {

    private val root: File get() = File(context.noBackupFilesDir, "telegram")

    override fun pathsFor(accountDir: String): TelegramTdlibPaths {
        val account = File(root, accountDir)
        val database = File(account, "database").apply { mkdirs() }
        val files = File(account, "files").apply { mkdirs() }
        return TelegramTdlibPaths(database.absolutePath, files.absolutePath)
    }

    override fun exists(path: String): Boolean = path.isNotBlank() && File(path).isFile

    override suspend fun stage(
        sourceUri: String,
        name: String,
        mimeType: String
    ): TelegramStagedFile = withContext(Dispatchers.IO) {
        val safeName = TelegramPolicy.sanitizeFileName(name)
        val directory = File(root, "${TelegramLimits.STAGED_DIRECTORY}/${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(directory, safeName)
        val input = context.contentResolver.openInputStream(Uri.parse(sourceUri))
            ?: error("The selected document is no longer readable")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        input.use { source ->
            target.outputStream().use { output ->
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= TelegramLimits.MAX_UPLOAD_BYTES) {
                        "Telegram accepts at most 2 GB per file for non-Premium accounts"
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        val hash = TelegramPolicy.hashStream(FileInputStream(target))
        require(hash.sizeBytes > 0L) { "The selected document is empty" }
        TelegramStagedFile(
            path = target.absolutePath,
            name = safeName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = hash.sizeBytes,
            sha256 = hash.sha256
        )
    }

    override suspend fun writeToDestination(
        localPath: String,
        destinationUri: String
    ): TelegramHashResult = withContext(Dispatchers.IO) {
        val source = File(localPath)
        require(source.isFile) { "TDLib's local copy disappeared; retry the download" }
        val output = context.contentResolver.openOutputStream(Uri.parse(destinationUri), "wt")
            ?: error("Cannot write to the selected destination")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        FileInputStream(source).use { input ->
            DigestOutputStream(output, digest).use { sink ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    sink.write(buffer, 0, read)
                    total += read
                }
            }
        }
        TelegramHashResult(total, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext
        runCatching {
            val file = File(path)
            file.delete()
            file.parentFile?.takeIf { it.name != TelegramLimits.STAGED_DIRECTORY }?.delete()
        }
        Unit
    }

    /**
     * Removes the TDLib database (which holds the session), its file cache, every staged upload
     * and every leftover preview. Encrypted SharedPreferences and the Keystore key are cleared
     * separately by TelegramSecureStore.clearAll().
     */
    override suspend fun wipe() = withContext(Dispatchers.IO) {
        listOf(root, File(context.filesDir, "tdlib"), File(context.cacheDir, "telegram_previews"))
            .forEach { directory -> runCatching { directory.deleteRecursively() } }
        // Revoke any persisted URI permission granted for a download destination.
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .forEach { grant ->
                runCatching {
                    context.revokeUriPermission(
                        grant.uri,
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
    }
}

/** Stream helper used when a caller needs to hash an InputStream it already owns. */
internal fun InputStream.copyWithLimit(target: java.io.OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    use { source ->
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= limit) { "File exceeds the Telegram upload limit" }
            target.write(buffer, 0, read)
        }
    }
    return total
}
