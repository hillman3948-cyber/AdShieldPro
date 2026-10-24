package com.adshield.pro.engine.dns

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * RFC 1035 / RFC 3596 wire-format DNS message parser and synthetic response builder.
 * Hardened against malformed packets, truncated labels, and compression-pointer loops.
 */
object DnsPacketCodec {

    const val TYPE_A = 1
    const val TYPE_NS = 2
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_PTR = 12
    const val TYPE_MX = 15
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65

    const val CLASS_IN = 1

    data class ParsedDnsQuery(
        val transactionId: Int,
        val flags: Int,
        val questionDomain: String,
        val qType: Int,
        val qClass: Int,
        val questionSectionEndOffset: Int,
        val rawPayload: ByteArray
    ) {
        val qTypeName: String
            get() = when (qType) {
                TYPE_A -> "A"
                TYPE_AAAA -> "AAAA"
                TYPE_CNAME -> "CNAME"
                TYPE_HTTPS -> "HTTPS"
                TYPE_SVCB -> "SVCB"
                TYPE_TXT -> "TXT"
                TYPE_MX -> "MX"
                TYPE_PTR -> "PTR"
                else -> "TYPE$qType"
            }

        val cacheKey: String
            get() = "${questionDomain.lowercase()}:$qType:$qClass"
    }

    /**
     * Parses a raw DNS query packet (without TCP 2-byte length prefix).
     * Returns null if the packet is malformed, truncated, or is already a response (QR == 1).
     */
    fun parseQuery(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset): ParsedDnsQuery? {
        if (length < 12 || offset < 0 || offset + length > payload.size) return null

        val txId = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        val flags = ((payload[offset + 2].toInt() and 0xFF) shl 8) or (payload[offset + 3].toInt() and 0xFF)

        // QR bit (highest bit of flags) must be 0 for a query
        val isResponse = (flags and 0x8000) != 0
        if (isResponse) return null

        val qdCount = ((payload[offset + 4].toInt() and 0xFF) shl 8) or (payload[offset + 5].toInt() and 0xFF)
        if (qdCount <= 0 || qdCount > 64) return null

        val limit = offset + length
        val nameResult = readDomainName(payload, offset, offset + 12, limit) ?: return null
        val afterNameOffset = nameResult.nextOffset

        // Need 4 bytes for QTYPE (2) and QCLASS (2)
        if (afterNameOffset + 4 > limit) return null

        val qType = ((payload[afterNameOffset].toInt() and 0xFF) shl 8) or
            (payload[afterNameOffset + 1].toInt() and 0xFF)
        val qClass = ((payload[afterNameOffset + 2].toInt() and 0xFF) shl 8) or
            (payload[afterNameOffset + 3].toInt() and 0xFF)

        val normalizedDomain = nameResult.domain.trimEnd('.').lowercase()
        if (normalizedDomain.isEmpty() || normalizedDomain.length > 253) return null

        val rawCopy = payload.copyOfRange(offset, limit)
        return ParsedDnsQuery(
            transactionId = txId,
            flags = flags,
            questionDomain = normalizedDomain,
            qType = qType,
            qClass = qClass,
            questionSectionEndOffset = (afterNameOffset + 4) - offset,
            rawPayload = rawCopy
        )
    }

    private data class DomainReadResult(
        val domain: String,
        val nextOffset: Int
    )

    private fun readDomainName(
        packet: ByteArray,
        msgStart: Int,
        startPos: Int,
        limit: Int
    ): DomainReadResult? {
        val sb = StringBuilder(64)
        var pos = startPos
        var nextPosAfterQuestion = -1
        var jumped = false
        var jumpCount = 0
        var totalChars = 0

        while (true) {
            if (pos >= limit || pos < msgStart) return null
            val lenByte = packet[pos].toInt() and 0xFF

            if (lenByte == 0) {
                if (!jumped) {
                    nextPosAfterQuestion = pos + 1
                }
                break
            }

            val pointerMask = lenByte and 0xC0
            if (pointerMask == 0xC0) {
                // RFC 1035 Compression pointer: 2 bytes
                if (pos + 1 >= limit) return null
                if (!jumped) {
                    nextPosAfterQuestion = pos + 2
                    jumped = true
                }
                jumpCount++
                if (jumpCount > 16) {
                    // Cyclic pointer loop protection
                    return null
                }
                val pointerOffset = ((lenByte and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                val targetPos = msgStart + pointerOffset
                if (targetPos < msgStart || targetPos >= limit || targetPos == pos) return null
                pos = targetPos
            } else if (pointerMask == 0x00) {
                // Standard label (1..63 bytes)
                val labelLen = lenByte
                if (labelLen > 63 || pos + 1 + labelLen > limit) return null
                if (sb.isNotEmpty()) {
                    sb.append('.')
                    totalChars++
                }
                for (i in 0 until labelLen) {
                    val ch = (packet[pos + 1 + i].toInt() and 0xFF).toChar()
                    // Reject control characters
                    if (ch.code < 0x20 || ch.code == 0x7F) return null
                    sb.append(ch)
                }
                totalChars += labelLen
                if (totalChars > 253) return null
                pos += 1 + labelLen
            } else {
                // Invalid label type bits (0x40 or 0x80)
                return null
            }
        }

        if (nextPosAfterQuestion < 0) return null
        return DomainReadResult(sb.toString(), nextPosAfterQuestion)
    }

    /**
     * Synthesizes a wire-format DNS response for a blocked domain.
     *
     * @param query Parsed incoming DNS query
     * @param sinkholeZeroIp If true, responds with 0.0.0.0 for A and :: for AAAA (RCODE=0);
     *                       if false, responds with NXDOMAIN (RCODE=3).
     * @param ttlSeconds TTL for the synthetic record (default 60 seconds).
     */
    fun buildBlockedResponse(
        query: ParsedDnsQuery,
        sinkholeZeroIp: Boolean,
        ttlSeconds: Int = 60
    ): ByteArray {
        val out = ByteArrayOutputStream(128)
        val rdBit = query.flags and 0x0100 // Preserve Recursion Desired bit

        val canSinkholeIp = sinkholeZeroIp && (query.qType == TYPE_A || query.qType == TYPE_AAAA)
        val rcode = if (sinkholeZeroIp) 0 else 3 // 0 = NOERROR, 3 = NXDOMAIN
        // QR = 1 (0x8000), AA = 0, RA = 1 (0x0080)
        val responseFlags = 0x8000 or rdBit or 0x0080 or rcode

        val answerCount = if (canSinkholeIp) 1 else 0

        // 12-byte DNS Header
        writeU16(out, query.transactionId)
        writeU16(out, responseFlags)
        writeU16(out, 1) // QDCOUNT = 1
        writeU16(out, answerCount) // ANCOUNT
        writeU16(out, 0) // NSCOUNT = 0
        writeU16(out, 0) // ARCOUNT = 0

        // Copy original Question Section (from byte 12 to questionSectionEndOffset)
        val qSectionLen = (query.questionSectionEndOffset - 12).coerceAtLeast(0)
        if (qSectionLen > 0 && 12 + qSectionLen <= query.rawPayload.size) {
            out.write(query.rawPayload, 12, qSectionLen)
        } else {
            // Fallback question encoding
            writeDomainLabels(out, query.questionDomain)
            writeU16(out, query.qType)
            writeU16(out, query.qClass)
        }

        if (canSinkholeIp) {
            // Name pointer to offset 12 (0xC00C)
            writeU16(out, 0xC00C)
            writeU16(out, query.qType)
            writeU16(out, CLASS_IN)
            writeU32(out, ttlSeconds.toLong())
            if (query.qType == TYPE_A) {
                writeU16(out, 4) // RDLENGTH = 4 (0.0.0.0)
                out.write(byteArrayOf(0, 0, 0, 0))
            } else {
                writeU16(out, 16) // RDLENGTH = 16 (::)
                out.write(ByteArray(16))
            }
        }

        return out.toByteArray()
    }

    /**
     * Synthesizes a SERVFAIL (RCODE = 2) DNS response when upstream resolution fails completely.
     */
    fun buildServFailResponse(query: ParsedDnsQuery): ByteArray {
        val out = ByteArrayOutputStream(64)
        val rdBit = query.flags and 0x0100
        val responseFlags = 0x8000 or rdBit or 0x0080 or 2 // RCODE = 2 (SERVFAIL)

        writeU16(out, query.transactionId)
        writeU16(out, responseFlags)
        writeU16(out, 1)
        writeU16(out, 0)
        writeU16(out, 0)
        writeU16(out, 0)

        val qSectionLen = (query.questionSectionEndOffset - 12).coerceAtLeast(0)
        if (qSectionLen > 0 && 12 + qSectionLen <= query.rawPayload.size) {
            out.write(query.rawPayload, 12, qSectionLen)
        }
        return out.toByteArray()
    }

    /**
     * Rewrites the 2-byte DNS Transaction ID on a cached DNS response.
     */
    fun rewriteTransactionId(dnsMessage: ByteArray, newTransactionId: Int): ByteArray {
        if (dnsMessage.size < 2) return dnsMessage
        val copy = dnsMessage.copyOf()
        copy[0] = ((newTransactionId ushr 8) and 0xFF).toByte()
        copy[1] = (newTransactionId and 0xFF).toByte()
        return copy
    }

    /**
     * Extracts a conservative cache TTL (in seconds) from an upstream DNS response, clamped to [15, 1800].
     */
    fun extractResponseTtlSeconds(dnsResponse: ByteArray): Int {
        if (dnsResponse.size < 12) return 60
        val flags = ((dnsResponse[2].toInt() and 0xFF) shl 8) or (dnsResponse[3].toInt() and 0xFF)
        val rcode = flags and 0x000F
        if (rcode != 0) return 30 // Short cache for NXDOMAIN

        val anCount = ((dnsResponse[6].toInt() and 0xFF) shl 8) or (dnsResponse[7].toInt() and 0xFF)
        if (anCount <= 0) return 60

        // Skip question section to inspect first answer TTL if easily reachable
        val qRead = readDomainName(dnsResponse, 0, 12, dnsResponse.size) ?: return 120
        val firstAnswerOffset = qRead.nextOffset + 4
        val nameRead = readDomainName(dnsResponse, 0, firstAnswerOffset, dnsResponse.size) ?: return 120
        val ttlOffset = nameRead.nextOffset + 4 // skip TYPE(2) + CLASS(2)
        if (ttlOffset + 4 <= dnsResponse.size) {
            val ttl = ByteBuffer.wrap(dnsResponse, ttlOffset, 4).int
            return ttl.coerceIn(15, 1800)
        }
        return 120
    }

    private fun writeDomainLabels(out: ByteArrayOutputStream, domain: String) {
        val labels = domain.trim('.').split('.')
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size and 0x3F)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }
}
