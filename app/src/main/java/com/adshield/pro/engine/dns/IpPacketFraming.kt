package com.adshield.pro.engine.dns

import java.nio.ByteBuffer

/**
 * IPv4 and IPv6 packet parser and response builder for the Android VpnService TUN interface.
 * Supports both UDP (Protocol 17) and TCP (Protocol 6) DNS packets with full RFC 1071 / RFC 8200
 * checksum calculation.
 */
object IpPacketFraming {

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    const val TCP_FLAG_FIN = 0x01
    const val TCP_FLAG_SYN = 0x02
    const val TCP_FLAG_RST = 0x04
    const val TCP_FLAG_PSH = 0x08
    const val TCP_FLAG_ACK = 0x10

    sealed class ParsedTransportPacket {
        abstract val ipVersion: Int
        abstract val sourceAddress: ByteArray
        abstract val destinationAddress: ByteArray
        abstract val sourcePort: Int
        abstract val destinationPort: Int

        data class UdpPacket(
            override val ipVersion: Int,
            override val sourceAddress: ByteArray,
            override val destinationAddress: ByteArray,
            override val sourcePort: Int,
            override val destinationPort: Int,
            val payload: ByteArray
        ) : ParsedTransportPacket()

        data class TcpPacket(
            override val ipVersion: Int,
            override val sourceAddress: ByteArray,
            override val destinationAddress: ByteArray,
            override val sourcePort: Int,
            override val destinationPort: Int,
            val sequenceNumber: Long,
            val acknowledgmentNumber: Long,
            val flags: Int,
            val windowSize: Int,
            val payload: ByteArray
        ) : ParsedTransportPacket()
    }

    /**
     * Parses a raw IPv4 or IPv6 packet read from the TUN interface.
     * Returns null if the packet is malformed or not UDP/TCP.
     */
    fun parseIpPacket(raw: ByteArray, length: Int = raw.size): ParsedTransportPacket? {
        if (length < 20 || length > raw.size) return null
        val version = (raw[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4Packet(raw, length)
            6 -> parseIpv6Packet(raw, length)
            else -> null
        }
    }

    private fun parseIpv4Packet(raw: ByteArray, length: Int): ParsedTransportPacket? {
        val ihl = raw[0].toInt() and 0x0F
        val ipHeaderLen = ihl * 4
        if (ihl < 5 || length < ipHeaderLen) return null

        val totalLen = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
        val effectiveLen = minOf(length, totalLen)
        if (effectiveLen < ipHeaderLen) return null

        // Check IPv4 fragmentation: ignore non-first fragments
        val flagsAndFragOffset = ((raw[6].toInt() and 0xFF) shl 8) or (raw[7].toInt() and 0xFF)
        if ((flagsAndFragOffset and 0x1FFF) != 0) return null

        val protocol = raw[9].toInt() and 0xFF
        val srcIp = raw.copyOfRange(12, 16)
        val dstIp = raw.copyOfRange(16, 20)

        return parseTransportLayer(
            ipVersion = 4,
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = protocol,
            raw = raw,
            transportOffset = ipHeaderLen,
            limit = effectiveLen
        )
    }

    private fun parseIpv6Packet(raw: ByteArray, length: Int): ParsedTransportPacket? {
        if (length < 40) return null
        val payloadLen = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
        val effectiveLen = minOf(length, 40 + payloadLen)
        if (effectiveLen < 40) return null

        var nextHeader = raw[6].toInt() and 0xFF
        val srcIp = raw.copyOfRange(8, 24)
        val dstIp = raw.copyOfRange(24, 40)

        var offset = 40
        var extLoops = 0
        // Skip standard IPv6 extension headers if present
        while (nextHeader in IPV6_EXTENSION_HEADERS && extLoops < 8) {
            extLoops++
            if (offset + 8 > effectiveLen) return null
            val hdrNext = raw[offset].toInt() and 0xFF
            val extLen = if (nextHeader == 44) {
                8 // Fragment header is fixed 8 bytes
            } else {
                ((raw[offset + 1].toInt() and 0xFF) + 1) * 8
            }
            if (extLen <= 0 || offset + extLen > effectiveLen) return null
            nextHeader = hdrNext
            offset += extLen
        }

        return parseTransportLayer(
            ipVersion = 6,
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = nextHeader,
            raw = raw,
            transportOffset = offset,
            limit = effectiveLen
        )
    }

    private fun parseTransportLayer(
        ipVersion: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        raw: ByteArray,
        transportOffset: Int,
        limit: Int
    ): ParsedTransportPacket? {
        return when (protocol) {
            PROTOCOL_UDP -> {
                if (transportOffset + 8 > limit) return null
                val srcPort = ((raw[transportOffset].toInt() and 0xFF) shl 8) or
                    (raw[transportOffset + 1].toInt() and 0xFF)
                val dstPort = ((raw[transportOffset + 2].toInt() and 0xFF) shl 8) or
                    (raw[transportOffset + 3].toInt() and 0xFF)
                val udpLen = ((raw[transportOffset + 4].toInt() and 0xFF) shl 8) or
                    (raw[transportOffset + 5].toInt() and 0xFF)
                if (udpLen < 8) return null
                val payloadEnd = minOf(limit, transportOffset + udpLen)
                val payloadStart = transportOffset + 8
                if (payloadStart > payloadEnd) return null
                ParsedTransportPacket.UdpPacket(
                    ipVersion = ipVersion,
                    sourceAddress = srcIp,
                    destinationAddress = dstIp,
                    sourcePort = srcPort,
                    destinationPort = dstPort,
                    payload = raw.copyOfRange(payloadStart, payloadEnd)
                )
            }
            PROTOCOL_TCP -> {
                if (transportOffset + 20 > limit) return null
                val srcPort = ((raw[transportOffset].toInt() and 0xFF) shl 8) or
                    (raw[transportOffset + 1].toInt() and 0xFF)
                val dstPort = ((raw[transportOffset + 2].toInt() and 0xFF) shl 8) or
                    (raw[transportOffset + 3].toInt() and 0xFF)
                val seq = ByteBuffer.wrap(raw, transportOffset + 4, 4).int.toLong() and 0xFFFFFFFFL
                val ack = ByteBuffer.wrap(raw, transportOffset + 8, 4).int.toLong() and 0xFFFFFFFFL
                val dataOffsetWords = (raw[transportOffset + 12].toInt() ushr 4) and 0x0F
                val tcpHeaderLen = dataOffsetWords * 4
                if (dataOffsetWords < 5 || transportOffset + tcpHeaderLen > limit) return null
                val flags = raw[transportOffset + 13].toInt() and 0x3F
                val window = ((raw[transportOffset + 14].toInt() and 0xFF) shl 8) or
                    (raw[transportOffset + 15].toInt() and 0xFF)
                val payload = raw.copyOfRange(transportOffset + tcpHeaderLen, limit)
                ParsedTransportPacket.TcpPacket(
                    ipVersion = ipVersion,
                    sourceAddress = srcIp,
                    destinationAddress = dstIp,
                    sourcePort = srcPort,
                    destinationPort = dstPort,
                    sequenceNumber = seq,
                    acknowledgmentNumber = ack,
                    flags = flags,
                    windowSize = window,
                    payload = payload
                )
            }
            else -> null
        }
    }

    /**
     * Builds a complete IPv4 or IPv6 UDP response packet ready to write back to the TUN descriptor.
     */
    fun buildUdpResponsePacket(
        request: ParsedTransportPacket.UdpPacket,
        dnsResponsePayload: ByteArray
    ): ByteArray {
        val udpLen = 8 + dnsResponsePayload.size
        val udpSegment = ByteArray(udpLen)
        // Swap source and destination ports
        writeU16(udpSegment, 0, request.destinationPort)
        writeU16(udpSegment, 2, request.sourcePort)
        writeU16(udpSegment, 4, udpLen)
        writeU16(udpSegment, 6, 0) // Checksum placeholder
        System.arraycopy(dnsResponsePayload, 0, udpSegment, 8, dnsResponsePayload.size)

        val udpChecksum = computeTransportChecksum(
            ipVersion = request.ipVersion,
            srcIp = request.destinationAddress,
            dstIp = request.sourceAddress,
            protocol = PROTOCOL_UDP,
            transportSegment = udpSegment
        )
        writeU16(udpSegment, 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)

        return wrapInIpHeader(
            ipVersion = request.ipVersion,
            srcIp = request.destinationAddress,
            dstIp = request.sourceAddress,
            protocol = PROTOCOL_UDP,
            transportSegment = udpSegment
        )
    }

    /**
     * Builds a complete IPv4 or IPv6 TCP response segment (for TCP port 53 queries on the virtual DNS IP).
     */
    fun buildTcpResponsePacket(
        request: ParsedTransportPacket.TcpPacket,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        tcpPayload: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpHeaderLen = 20
        val tcpSegment = ByteArray(tcpHeaderLen + tcpPayload.size)
        writeU16(tcpSegment, 0, request.destinationPort)
        writeU16(tcpSegment, 2, request.sourcePort)
        writeU32(tcpSegment, 4, seqNum)
        writeU32(tcpSegment, 8, ackNum)
        tcpSegment[12] = ((5 shl 4) and 0xF0).toByte() // Data offset = 5 (20 bytes)
        tcpSegment[13] = (flags and 0x3F).toByte()
        writeU16(tcpSegment, 14, 65535) // Window size
        writeU16(tcpSegment, 16, 0) // Checksum placeholder
        writeU16(tcpSegment, 18, 0) // Urgent pointer
        if (tcpPayload.isNotEmpty()) {
            System.arraycopy(tcpPayload, 0, tcpSegment, tcpHeaderLen, tcpPayload.size)
        }

        val tcpChecksum = computeTransportChecksum(
            ipVersion = request.ipVersion,
            srcIp = request.destinationAddress,
            dstIp = request.sourceAddress,
            protocol = PROTOCOL_TCP,
            transportSegment = tcpSegment
        )
        writeU16(tcpSegment, 16, tcpChecksum)

        return wrapInIpHeader(
            ipVersion = request.ipVersion,
            srcIp = request.destinationAddress,
            dstIp = request.sourceAddress,
            protocol = PROTOCOL_TCP,
            transportSegment = tcpSegment
        )
    }

    private fun wrapInIpHeader(
        ipVersion: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        transportSegment: ByteArray
    ): ByteArray {
        return if (ipVersion == 4) {
            val totalLen = 20 + transportSegment.size
            val packet = ByteArray(totalLen)
            packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
            packet[1] = 0x00.toByte() // DSCP/ECN
            writeU16(packet, 2, totalLen)
            writeU16(packet, 4, 0x1337) // Identification
            writeU16(packet, 6, 0x4000) // Flags: Don't Fragment
            packet[8] = 64.toByte() // TTL
            packet[9] = protocol.toByte()
            writeU16(packet, 10, 0) // Header checksum placeholder
            System.arraycopy(srcIp, 0, packet, 12, 4)
            System.arraycopy(dstIp, 0, packet, 16, 4)
            val ipChecksum = computeOnesComplementChecksum(packet, 0, 20)
            writeU16(packet, 10, ipChecksum)
            System.arraycopy(transportSegment, 0, packet, 20, transportSegment.size)
            packet
        } else {
            val totalLen = 40 + transportSegment.size
            val packet = ByteArray(totalLen)
            packet[0] = 0x60.toByte() // Version 6
            packet[1] = 0x00.toByte()
            packet[2] = 0x00.toByte()
            packet[3] = 0x00.toByte()
            writeU16(packet, 4, transportSegment.size) // Payload length
            packet[6] = protocol.toByte() // Next Header
            packet[7] = 64.toByte() // Hop Limit
            System.arraycopy(srcIp, 0, packet, 8, 16)
            System.arraycopy(dstIp, 0, packet, 24, 16)
            System.arraycopy(transportSegment, 0, packet, 40, transportSegment.size)
            packet
        }
    }

    /**
     * Computes RFC 1071 ones'-complement checksum over [data] slice.
     */
    fun computeOnesComplementChecksum(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    fun computeTransportChecksum(
        ipVersion: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        transportSegment: ByteArray
    ): Int {
        val pseudoHeaderLen = if (ipVersion == 4) 12 else 40
        val buf = ByteArray(pseudoHeaderLen + transportSegment.size)
        if (ipVersion == 4) {
            System.arraycopy(srcIp, 0, buf, 0, 4)
            System.arraycopy(dstIp, 0, buf, 4, 4)
            buf[8] = 0
            buf[9] = protocol.toByte()
            writeU16(buf, 10, transportSegment.size)
        } else {
            System.arraycopy(srcIp, 0, buf, 0, 16)
            System.arraycopy(dstIp, 0, buf, 16, 16)
            writeU32(buf, 32, transportSegment.size.toLong())
            buf[36] = 0
            buf[37] = 0
            buf[38] = 0
            buf[39] = protocol.toByte()
        }
        System.arraycopy(transportSegment, 0, buf, pseudoHeaderLen, transportSegment.size)
        return computeOnesComplementChecksum(buf, 0, buf.size)
    }

    private val IPV6_EXTENSION_HEADERS = setOf(0, 43, 44, 60)

    private fun writeU16(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = ((value ushr 8) and 0xFF).toByte()
        arr[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(arr: ByteArray, offset: Int, value: Long) {
        arr[offset] = ((value ushr 24) and 0xFF).toByte()
        arr[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        arr[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        arr[offset + 3] = (value and 0xFF).toByte()
    }
}
