package com.adshield.pro.engine.dns

import android.util.LruCache
import com.adshield.pro.domain.model.DnsProtocol
import com.adshield.pro.domain.model.DnsProviderPreset
import com.adshield.pro.domain.model.DnsResolverConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Multi-protocol Upstream DNS Client supporting:
 * - DNS-over-HTTPS (RFC 8484 wireformat)
 * - DNS-over-TLS (RFC 7858 port 853 with TLS hostname verification)
 * - Protected UDP & TCP Port 53 with automatic TCP fallback on truncation (TC=1)
 * - Thread-safe TTL-aware LRU DNS cache
 */
@Singleton
class UpstreamDnsResolver @Inject constructor() {

    data class UpstreamResult(
        val responseBytes: ByteArray,
        val protocolUsed: String,
        val fromCache: Boolean,
        val latencyMs: Int
    )

    private data class CachedDnsEntry(
        val wireResponse: ByteArray,
        val expiresAtEpochMs: Long,
        val protocolOrigin: String
    )

    private val dnsCache = object : LruCache<String, CachedDnsEntry>(1024) {}

    @Volatile
    private var currentProtector: ((Socket) -> Boolean)? = null

    @Volatile
    private var currentDatagramProtector: ((DatagramSocket) -> Boolean)? = null

    fun bindVpnSocketProtectors(
        protectTcp: ((Socket) -> Boolean)?,
        protectUdp: ((DatagramSocket) -> Boolean)?
    ) {
        currentProtector = protectTcp
        currentDatagramProtector = protectUdp
    }

    fun clearCache() {
        synchronized(dnsCache) {
            dnsCache.evictAll()
        }
    }

    fun getCacheSize(): Int {
        synchronized(dnsCache) {
            return dnsCache.size()
        }
    }

    fun trimExpiredAndExcessCache(targetSize: Int = 128): Int {
        synchronized(dnsCache) {
            val before = dnsCache.size()
            val now = System.currentTimeMillis()
            val snapshot = dnsCache.snapshot()
            for ((key, entry) in snapshot) {
                if (now >= entry.expiresAtEpochMs) {
                    dnsCache.remove(key)
                }
            }
            dnsCache.trimToSize(targetSize.coerceAtLeast(0))
            return (before - dnsCache.size()).coerceAtLeast(0)
        }
    }

    private val protectedSocketFactory = object : SocketFactory() {
        private val delegate = getDefault()

        private fun protect(socket: Socket): Socket {
            currentProtector?.invoke(socket)
            return socket
        }

        override fun createSocket(): Socket = protect(delegate.createSocket())
        override fun createSocket(host: String?, port: Int): Socket = protect(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            protect(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = protect(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            protect(delegate.createSocket(address, port, localAddress, localPort))
    }

    /**
     * Bootstrap DNS resolver for DoH endpoints so OkHttp never triggers a recursive DNS lookup
     * through the VPN tunnel when resolving well-known DoH hostnames.
     */
    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val lower = hostname.lowercase()
            val presetIps = when (lower) {
                "cloudflare-dns.com", "one.one.one.one" -> listOf("1.1.1.1", "1.0.0.1")
                "dns.quad9.net" -> listOf("9.9.9.9", "149.112.112.112")
                "unfiltered.adguard-dns.com", "dns.adguard-dns.com" -> listOf("94.140.14.140", "94.140.14.141")
                "dns.google" -> listOf("8.8.8.8", "8.8.4.4")
                else -> emptyList()
            }
            if (presetIps.isNotEmpty()) {
                return presetIps.mapNotNull { ip ->
                    runCatching { InetAddress.getByName(ip) }.getOrNull()
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .socketFactory(protectedSocketFactory)
            .dns(bootstrapDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Resolves a parsed DNS query against the configured upstream resolver (or serves from TTL cache).
     */
    suspend fun resolve(
        query: DnsPacketCodec.ParsedDnsQuery,
        config: DnsResolverConfig
    ): UpstreamResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cacheKey = query.cacheKey

        // 1. Check LRU TTL Cache
        val cached = synchronized(dnsCache) { dnsCache.get(cacheKey) }
        if (cached != null) {
            if (cached.expiresAtEpochMs > now) {
                val rewritten = DnsPacketCodec.rewriteTransactionId(cached.wireResponse, query.transactionId)
                return@withContext UpstreamResult(
                    responseBytes = rewritten,
                    protocolUsed = "${cached.protocolOrigin} (Cached)",
                    fromCache = true,
                    latencyMs = 0
                )
            } else {
                synchronized(dnsCache) { dnsCache.remove(cacheKey) }
            }
        }

        val startMs = System.currentTimeMillis()

        // 2. Use only the protocol selected by the user. Encrypted DNS must never silently
        // downgrade to plaintext UDP/TCP when its upstream is unavailable.
        val (responseBytes, protocolUsed) = when (config.protocol) {
            DnsProtocol.DOH -> {
                val dohRes = runCatching { queryDoh(query.rawPayload, config.activeDohUrl) }.getOrNull()
                if (dohRes != null) {
                    dohRes to "DoH (${config.preset.displayName})"
                } else {
                    DnsPacketCodec.buildServFailResponse(query) to "SERVFAIL (DoH unavailable)"
                }
            }
            DnsProtocol.DOT -> {
                val dotRes = runCatching { queryDot(query.rawPayload, config) }.getOrNull()
                if (dotRes != null) {
                    dotRes to "DoT (${config.activeDotHost})"
                } else {
                    DnsPacketCodec.buildServFailResponse(query) to "SERVFAIL (DoT unavailable)"
                }
            }
            DnsProtocol.UDP_TCP -> {
                val udpRes = runCatching { queryUdpOrTcp(query, config) }.getOrNull()
                if (udpRes != null) {
                    udpRes to "UDP/53 (${config.activePrimaryIpv4})"
                } else {
                    DnsPacketCodec.buildServFailResponse(query) to "SERVFAIL"
                }
            }
        }

        val latency = (System.currentTimeMillis() - startMs).toInt().coerceAtLeast(1)

        // 3. Store in cache if valid response (not SERVFAIL)
        if (!protocolUsed.startsWith("SERVFAIL") && responseBytes.size >= 12) {
            val ttlSeconds = DnsPacketCodec.extractResponseTtlSeconds(responseBytes)
            synchronized(dnsCache) {
                dnsCache.put(
                    cacheKey,
                    CachedDnsEntry(
                        wireResponse = responseBytes,
                        expiresAtEpochMs = System.currentTimeMillis() + (ttlSeconds * 1000L),
                        protocolOrigin = config.protocol.name
                    )
                )
            }
        }

        UpstreamResult(
            responseBytes = responseBytes,
            protocolUsed = protocolUsed,
            fromCache = false,
            latencyMs = latency
        )
    }

    /**
     * Executes an RFC 8484 DNS-over-HTTPS wireformat POST query.
     */
    fun queryDoh(rawDnsQuery: ByteArray, dohUrl: String): ByteArray {
        val requestBody = rawDnsQuery.toRequestBody(DNS_MESSAGE_MEDIA_TYPE)
        val request = Request.Builder()
            .url(dohUrl)
            .header("Accept", "application/dns-message")
            .post(requestBody)
            .build()

        dohHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("DoH HTTP ${response.code}")
            }
            val bytes = response.body?.bytes()
                ?: throw IllegalStateException("Empty DoH response body")
            if (bytes.size < 12) {
                throw IllegalStateException("Truncated DoH DNS message (${bytes.size} bytes)")
            }
            return bytes
        }
    }

    /**
     * Executes an RFC 7858 DNS-over-TLS query over port 853 with SNI and certificate verification.
     */
    fun queryDot(rawDnsQuery: ByteArray, config: DnsResolverConfig): ByteArray {
        val rawSocket = Socket()
        currentProtector?.invoke(rawSocket)
        val targetIp = InetAddress.getByName(config.activePrimaryIpv4)
        rawSocket.connect(InetSocketAddress(targetIp, config.preset.dotPort), 3500)
        rawSocket.soTimeout = 3500

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val dotHost = config.activeDotHost
        val sslSocket = sslFactory.createSocket(rawSocket, dotHost, config.preset.dotPort, true) as SSLSocket
        sslSocket.use { tls ->
            tls.startHandshake()
            val session = tls.session
            val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(dotHost, session)
            if (!verified) {
                throw SecurityException("DoT TLS certificate hostname verification failed for $dotHost")
            }

            val out = DataOutputStream(tls.outputStream)
            val input = DataInputStream(tls.inputStream)

            // RFC 7858 / RFC 1035 Section 4.2.2: 2-byte unsigned length prefix
            out.writeShort(rawDnsQuery.size)
            out.write(rawDnsQuery)
            out.flush()

            val responseLen = input.readUnsignedShort()
            if (responseLen < 12 || responseLen > 65535) {
                throw IllegalStateException("Invalid DoT response length: $responseLen")
            }
            val responseBuf = ByteArray(responseLen)
            input.readFully(responseBuf)
            return responseBuf
        }
    }

    /**
     * Executes a protected UDP port 53 DNS query, automatically upgrading to protected TCP port 53
     * if the response has the TC (Truncated) bit set.
     */
    fun queryUdpOrTcp(
        query: DnsPacketCodec.ParsedDnsQuery,
        config: DnsResolverConfig
    ): ByteArray {
        val servers = listOf(config.activePrimaryIpv4, config.activeSecondaryIpv4).filter { it.isNotBlank() }
        var lastError: Throwable? = null

        for (serverIp in servers) {
            try {
                val udpResponse = querySingleUdpServer(query.rawPayload, serverIp)
                // Check TC (Truncation) bit (0x0200 in flags)
                val flags = ((udpResponse[2].toInt() and 0xFF) shl 8) or (udpResponse[3].toInt() and 0xFF)
                if ((flags and 0x0200) != 0) {
                    return querySingleTcpServer(query.rawPayload, serverIp)
                }
                return udpResponse
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("All upstream UDP/TCP DNS servers failed")
    }

    private fun querySingleUdpServer(rawDnsQuery: ByteArray, serverIp: String): ByteArray {
        DatagramSocket().use { socket ->
            currentDatagramProtector?.invoke(socket)
            socket.soTimeout = 2500
            val address = InetAddress.getByName(serverIp)
            val requestPacket = DatagramPacket(rawDnsQuery, rawDnsQuery.size, address, 53)
            socket.send(requestPacket)

            val receiveBuf = ByteArray(4096)
            val responsePacket = DatagramPacket(receiveBuf, receiveBuf.size)
            socket.receive(responsePacket)
            if (responsePacket.length < 12) {
                throw IllegalStateException("Short UDP DNS response (${responsePacket.length} bytes)")
            }
            return receiveBuf.copyOfRange(0, responsePacket.length)
        }
    }

    fun querySingleTcpServer(rawDnsQuery: ByteArray, serverIp: String): ByteArray {
        Socket().use { socket ->
            currentProtector?.invoke(socket)
            socket.connect(InetSocketAddress(InetAddress.getByName(serverIp), 53), 3000)
            socket.soTimeout = 3000
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            out.writeShort(rawDnsQuery.size)
            out.write(rawDnsQuery)
            out.flush()
            val len = input.readUnsignedShort()
            if (len < 12) throw IllegalStateException("Short TCP DNS response: $len")
            val buf = ByteArray(len)
            input.readFully(buf)
            return buf
        }
    }

    companion object {
        private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()
    }
}
