package com.adshield.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Process
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.adshield.pro.MainActivity
import com.adshield.pro.R
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.data.repository.AdShieldRepository
import com.adshield.pro.domain.model.VpnStatus
import com.adshield.pro.engine.dns.DnsPacketCodec
import com.adshield.pro.engine.dns.IpPacketFraming
import com.adshield.pro.engine.dns.UpstreamDnsResolver
import com.adshield.pro.engine.filter.FilterRuleEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import javax.inject.Inject

/**
 * Layer 1 & Layer 2 — Production Local DNS-Filtering VPN Service.
 *
 * Key architectural guarantees:
 * - Routes ONLY virtual DNS server addresses (`10.215.173.2/32` and `fd00:ad5:1e1d::2/128`) into the TUN interface.
 * - All non-DNS TCP/UDP/QUIC application traffic flows directly over the physical network interface
 *   with zero throughput bottleneck and zero HTTPS payload interception.
 * - Protects all upstream DoH, DoT, and UDP/TCP sockets via [VpnService.protect] to prevent routing loops.
 * - Dynamically applies rule edits and protection mode changes in memory without restarting the tunnel.
 */
@AndroidEntryPoint
class AdShieldVpnService : VpnService() {

    @Inject lateinit var repository: AdShieldRepository
    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var filterRuleEngine: FilterRuleEngine
    @Inject lateinit var upstreamDnsResolver: UpstreamDnsResolver

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null
    private val tunWriteLock = Any()
    private data class AppAttribution(val packageName: String, val label: String)
    private val appAttributionByUid = ConcurrentHashMap<Int, AppAttribution>()

    @Volatile
    private var isPaused: Boolean = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        upstreamDnsResolver.bindVpnSocketProtectors(
            protectTcp = { socket -> protect(socket) },
            protectUdp = { datagramSocket -> protect(datagramSocket) }
        )
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                serviceScope.launch {
                    preferencesRepository.setVpnShouldRemainActive(false)
                }
                stopVpnTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_VPN -> {
                isPaused = true
                repository.updateVpnStatus(VpnStatus.PAUSED)
                updateForegroundNotification()
                return START_STICKY
            }
            ACTION_RESUME_VPN -> {
                isPaused = false
                if (vpnInterface != null && tunnelJob?.isActive == true) {
                    repository.updateVpnStatus(VpnStatus.CONNECTED)
                    updateForegroundNotification()
                } else {
                    startVpnTunnel()
                }
                return START_STICKY
            }
            ACTION_RELOAD_TUNNEL_CONFIG -> {
                // Used only when per-app VPN exclusions or IPv6 interface routing changes
                if (vpnInterface != null) {
                    startVpnTunnel()
                }
                return START_STICKY
            }
            else -> {
                startForegroundWithNotification()
                startVpnTunnel()
                return START_STICKY
            }
        }
    }

    private fun startVpnTunnel() {
        tunnelJob?.cancel()
        closeTunInterface()

        repository.updateVpnStatus(VpnStatus.STARTING)

        tunnelJob = serviceScope.launch {
            try {
                val settings = preferencesRepository.getSettingsSnapshot()
                preferencesRepository.setVpnShouldRemainActive(true)
                filterRuleEngine.updateProtectionMode(settings.protectionMode)
                repository.syncRuntimeModeAndResolver(settings.protectionMode, settings)

                val builder = Builder()
                    .setSession(getString(R.string.vpn_session_name))
                    .setMtu(VPN_MTU)
                    .setBlocking(true)
                    // IPv4 Virtual Tunnel & DNS Sinkhole Router
                    .addAddress(VIRTUAL_IPV4_CLIENT, 30)
                    .addRoute(VIRTUAL_IPV4_DNS, 32)
                    .addDnsServer(VIRTUAL_IPV4_DNS)

                if (settings.resolverConfig.enableIpv6) {
                    builder.addAddress(VIRTUAL_IPV6_CLIENT, 126)
                    builder.addRoute(VIRTUAL_IPV6_DNS, 128)
                    builder.addDnsServer(VIRTUAL_IPV6_DNS)
                }

                // Always exclude AdShield Pro itself so upstream requests never loop
                runCatching { builder.addDisallowedApplication(packageName) }

                // Apply per-app VPN exclusions configured by the user
                for (excludedPkg in settings.excludedAppPackages) {
                    if (excludedPkg.isNotBlank() && excludedPkg != packageName) {
                        runCatching { builder.addDisallowedApplication(excludedPkg) }
                    }
                }

                val configureIntent = PendingIntent.getActivity(
                    this@AdShieldVpnService,
                    0,
                    Intent(this@AdShieldVpnService, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.setConfigureIntent(configureIntent)

                val establishedPfd = builder.establish()
                if (establishedPfd == null) {
                    repository.updateVpnStatus(
                        status = VpnStatus.ERROR,
                        error = "VPN permission not granted or another Always-On VPN is blocking tunnel creation."
                    )
                    stopSelf()
                    return@launch
                }

                vpnInterface = establishedPfd
                isPaused = false
                repository.updateVpnStatus(
                    status = VpnStatus.CONNECTED,
                    networkLabel = detectCurrentNetworkLabel()
                )
                updateForegroundNotification()

                runPacketLoop(establishedPfd)
            } catch (t: Throwable) {
                repository.updateVpnStatus(
                    status = VpnStatus.ERROR,
                    error = "VPN Engine Error: ${t.message ?: t.javaClass.simpleName}"
                )
                stopSelf()
            }
        }
    }

    private suspend fun runPacketLoop(pfd: ParcelFileDescriptor) {
        val inputStream = FileInputStream(pfd.fileDescriptor)
        val outputStream = FileOutputStream(pfd.fileDescriptor)
        val packetBuffer = ByteArray(MAX_PACKET_SIZE)

        try {
            while (serviceScope.isActive && vpnInterface != null) {
                val length = try {
                    inputStream.read(packetBuffer)
                } catch (_: InterruptedIOException) {
                    break
                } catch (e: Exception) {
                    if (!serviceScope.isActive) break
                    continue
                }

                if (length <= 0) continue

                val parsedPacket = IpPacketFraming.parseIpPacket(packetBuffer, length) ?: continue

                when (parsedPacket) {
                    is IpPacketFraming.ParsedTransportPacket.UdpPacket -> {
                        if (parsedPacket.destinationPort == 53) {
                            serviceScope.launch {
                                handleUdpDnsPacket(parsedPacket, outputStream)
                            }
                        }
                    }
                    is IpPacketFraming.ParsedTransportPacket.TcpPacket -> {
                        if (parsedPacket.destinationPort == 53) {
                            serviceScope.launch {
                                handleTcpDnsPacket(parsedPacket, outputStream)
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { inputStream.close() }
            runCatching { outputStream.close() }
        }
    }

    private suspend fun handleUdpDnsPacket(
        udpPacket: IpPacketFraming.ParsedTransportPacket.UdpPacket,
        outputStream: FileOutputStream
    ) {
        val dnsQuery = DnsPacketCodec.parseQuery(udpPacket.payload) ?: return
        val app = resolveDnsOwner(IpPacketFraming.PROTOCOL_UDP, udpPacket)
        val dnsResponseBytes = processDnsQuery(dnsQuery, app)
        val responseIpPacket = IpPacketFraming.buildUdpResponsePacket(udpPacket, dnsResponseBytes)
        writeToTunSafe(outputStream, responseIpPacket)
    }

    /**
     * Lightweight stateless TCP port 53 responder for the virtual DNS IP:
     * - SYN -> SYN+ACK
     * - PSH+ACK / payload with 2-byte length prefix -> DNS response with 2-byte length prefix + ACK
     * - FIN -> FIN+ACK
     */
    private suspend fun handleTcpDnsPacket(
        tcpPacket: IpPacketFraming.ParsedTransportPacket.TcpPacket,
        outputStream: FileOutputStream
    ) {
        val flags = tcpPacket.flags
        if ((flags and IpPacketFraming.TCP_FLAG_RST) != 0) return

        if ((flags and IpPacketFraming.TCP_FLAG_SYN) != 0 && (flags and IpPacketFraming.TCP_FLAG_ACK) == 0) {
            val synAck = IpPacketFraming.buildTcpResponsePacket(
                request = tcpPacket,
                seqNum = 1000L,
                ackNum = (tcpPacket.sequenceNumber + 1L) and 0xFFFFFFFFL,
                flags = IpPacketFraming.TCP_FLAG_SYN or IpPacketFraming.TCP_FLAG_ACK
            )
            writeToTunSafe(outputStream, synAck)
            return
        }

        if (tcpPacket.payload.size >= 14) {
            val dnsLen = ((tcpPacket.payload[0].toInt() and 0xFF) shl 8) or
                (tcpPacket.payload[1].toInt() and 0xFF)
            if (dnsLen >= 12 && 2 + dnsLen <= tcpPacket.payload.size) {
                val dnsQuery = DnsPacketCodec.parseQuery(tcpPacket.payload, 2, dnsLen)
                if (dnsQuery != null) {
                    val app = resolveDnsOwner(IpPacketFraming.PROTOCOL_TCP, tcpPacket)
                    val dnsResp = processDnsQuery(dnsQuery, app)
                    val tcpDnsPayload = ByteArray(2 + dnsResp.size)
                    tcpDnsPayload[0] = ((dnsResp.size ushr 8) and 0xFF).toByte()
                    tcpDnsPayload[1] = (dnsResp.size and 0xFF).toByte()
                    System.arraycopy(dnsResp, 0, tcpDnsPayload, 2, dnsResp.size)

                    val respSegment = IpPacketFraming.buildTcpResponsePacket(
                        request = tcpPacket,
                        seqNum = tcpPacket.acknowledgmentNumber,
                        ackNum = (tcpPacket.sequenceNumber + tcpPacket.payload.size) and 0xFFFFFFFFL,
                        flags = IpPacketFraming.TCP_FLAG_PSH or IpPacketFraming.TCP_FLAG_ACK,
                        tcpPayload = tcpDnsPayload
                    )
                    writeToTunSafe(outputStream, respSegment)
                }
            }
        }

        if ((flags and IpPacketFraming.TCP_FLAG_FIN) != 0) {
            val finAck = IpPacketFraming.buildTcpResponsePacket(
                request = tcpPacket,
                seqNum = tcpPacket.acknowledgmentNumber,
                ackNum = (tcpPacket.sequenceNumber + tcpPacket.payload.size + 1L) and 0xFFFFFFFFL,
                flags = IpPacketFraming.TCP_FLAG_FIN or IpPacketFraming.TCP_FLAG_ACK
            )
            writeToTunSafe(outputStream, finAck)
        }
    }

    private fun resolveDnsOwner(
        protocol: Int,
        packet: IpPacketFraming.ParsedTransportPacket
    ): AppAttribution? = runCatching {
        val manager = connectivityManager ?: return null
        val uid = manager.getConnectionOwnerUid(
            protocol,
            InetSocketAddress(InetAddress.getByAddress(packet.sourceAddress), packet.sourcePort),
            InetSocketAddress(InetAddress.getByAddress(packet.destinationAddress), packet.destinationPort)
        )
        if (uid == Process.INVALID_UID) return null
        appAttributionByUid[uid]?.let { return it }
        val packageName = packageManager.getPackagesForUid(uid)
            ?.firstOrNull { it != this.packageName }
            ?: packageManager.getPackagesForUid(uid)?.firstOrNull()
            ?: return null
        val appInfo = packageManager.getApplicationInfo(packageName, 0)
        val attribution = AppAttribution(packageName, packageManager.getApplicationLabel(appInfo).toString())
        appAttributionByUid[uid] = attribution
        attribution
    }.getOrNull()

    private suspend fun processDnsQuery(
        query: DnsPacketCodec.ParsedDnsQuery,
        app: AppAttribution?
    ): ByteArray {
        val currentSettings = preferencesRepository.getSettingsSnapshot()

        // If protection is temporarily paused by user, forward directly to encrypted upstream DNS
        if (isPaused) {
            val upstream = upstreamDnsResolver.resolve(query, currentSettings.resolverConfig)
            repository.recordDnsQueryEvent(
                domain = query.questionDomain,
                queryType = query.qTypeName,
                isBlocked = false,
                category = null,
                matchedRule = null,
                sourceList = "Paused (Passthrough)",
                latencyMs = upstream.latencyMs,
                protocolUsed = upstream.protocolUsed,
                fromCache = upstream.fromCache,
                appPackageName = app?.packageName,
                appName = app?.label
            )
            return upstream.responseBytes
        }

        // Layer 1 & Layer 4: Evaluate queried domain against FilterRuleEngine
        val evaluation = filterRuleEngine.evaluate(query.questionDomain)

        if (evaluation.isBlocked) {
            val mode = filterRuleEngine.getCurrentSnapshot().protectionMode
            val blockedBytes = DnsPacketCodec.buildBlockedResponse(
                query = query,
                sinkholeZeroIp = mode.blockSinkholeZeroIp
            )
            repository.recordDnsQueryEvent(
                domain = query.questionDomain,
                queryType = query.qTypeName,
                isBlocked = true,
                category = evaluation.matchedCategory,
                matchedRule = evaluation.matchedRule,
                sourceList = evaluation.sourceName,
                latencyMs = 0,
                protocolUsed = if (mode.blockSinkholeZeroIp) "Sinkhole 0.0.0.0" else "Local NXDOMAIN",
                fromCache = false,
                appPackageName = app?.packageName,
                appName = app?.label
            )
            maybeRefreshNotificationCounter()
            return blockedBytes
        }

        // Allowed domain: resolve via configured DoH / DoT / Protected UDP-TCP resolver
        val upstreamResult = upstreamDnsResolver.resolve(query, currentSettings.resolverConfig)
        repository.recordDnsQueryEvent(
            domain = query.questionDomain,
            queryType = query.qTypeName,
            isBlocked = false,
            category = null,
            matchedRule = evaluation.matchedRule,
            sourceList = evaluation.sourceName,
            latencyMs = upstreamResult.latencyMs,
            protocolUsed = upstreamResult.protocolUsed,
            fromCache = upstreamResult.fromCache,
            appPackageName = app?.packageName,
            appName = app?.label
        )
        maybeRefreshNotificationCounter()
        return upstreamResult.responseBytes
    }

    private fun writeToTunSafe(outputStream: FileOutputStream, packetBytes: ByteArray) {
        synchronized(tunWriteLock) {
            runCatching {
                outputStream.write(packetBytes)
                outputStream.flush()
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { setUnderlyingNetworks(arrayOf(network)) }
                val label = detectCurrentNetworkLabel()
                if (vpnInterface != null && !isPaused) {
                    repository.updateVpnStatus(VpnStatus.CONNECTED, networkLabel = label)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val label = detectCurrentNetworkLabel()
                if (vpnInterface != null && !isPaused) {
                    repository.updateVpnStatus(VpnStatus.CONNECTED, networkLabel = label)
                }
            }

            override fun onLost(network: Network) {
                val label = detectCurrentNetworkLabel()
                if (vpnInterface != null && !isPaused) {
                    repository.updateVpnStatus(VpnStatus.CONNECTED, networkLabel = label)
                }
            }
        }

        networkCallback = callback
        runCatching {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(req, callback)
        }
    }

    private fun detectCurrentNetworkLabel(): String {
        val cm = connectivityManager ?: (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: return "Active Network"
        val activeNetwork = cm.activeNetwork ?: return "Offline / Switching"
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return "Active Network"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    private var lastNotificationUpdateMs = 0L

    private fun maybeRefreshNotificationCounter() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= 4000L) {
            lastNotificationUpdateMs = now
            updateForegroundNotification()
        }
    }

    private fun startForegroundWithNotification() {
        val notification = buildStatusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildStatusNotification())
    }

    private fun buildStatusNotification(): Notification {
        val state = repository.vpnRuntimeState.value
        val openAppIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AdShieldVpnService::class.java).apply { action = ACTION_STOP_VPN },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePauseAction = if (isPaused) {
            val resumePending = PendingIntent.getService(
                this,
                3,
                Intent(this, AdShieldVpnService::class.java).apply { action = ACTION_RESUME_VPN },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(0, "Resume", resumePending).build()
        } else {
            val pausePending = PendingIntent.getService(
                this,
                3,
                Intent(this, AdShieldVpnService::class.java).apply { action = ACTION_PAUSE_VPN },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(0, "Pause", pausePending).build()
        }

        val title = if (isPaused) {
            "AdShield Pro — Protection Paused"
        } else {
            "AdShield Pro Active (${state.activeMode.title})"
        }

        val content = "Blocked ${state.sessionBlockedQueries} of ${state.sessionTotalQueries} DNS queries • ${state.resolverConfig.protocol.name}"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_logo)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent)
            .addAction(togglePauseAction)
            .addAction(NotificationCompat.Action.Builder(0, "Stop", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.vpn_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.vpn_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onRevoke() {
        // Triggered when the user or another VPN application revokes our VPN permission
        serviceScope.launch {
            preferencesRepository.setVpnShouldRemainActive(false)
        }
        stopVpnTunnel(reason = "VPN permission was revoked or superseded by another VPN app.")
        stopSelf()
        super.onRevoke()
    }

    private fun stopVpnTunnel(reason: String? = null) {
        tunnelJob?.cancel()
        tunnelJob = null
        closeTunInterface()
        repository.updateVpnStatus(VpnStatus.DISCONNECTED, error = reason)
    }

    private fun closeTunInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        upstreamDnsResolver.bindVpnSocketProtectors(null, null)
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_VPN = "com.adshield.pro.action.START_VPN"
        const val ACTION_STOP_VPN = "com.adshield.pro.action.STOP_VPN"
        const val ACTION_PAUSE_VPN = "com.adshield.pro.action.PAUSE_VPN"
        const val ACTION_RESUME_VPN = "com.adshield.pro.action.RESUME_VPN"
        const val ACTION_RELOAD_TUNNEL_CONFIG = "com.adshield.pro.action.RELOAD_TUNNEL_CONFIG"

        private const val NOTIFICATION_CHANNEL_ID = "adshield_vpn_channel"
        private const val NOTIFICATION_ID = 2001

        private const val VPN_MTU = 1500
        private const val MAX_PACKET_SIZE = 32767

        const val VIRTUAL_IPV4_CLIENT = "10.215.173.1"
        const val VIRTUAL_IPV4_DNS = "10.215.173.2"
        const val VIRTUAL_IPV6_CLIENT = "fd00:ad5:1e1d::1"
        const val VIRTUAL_IPV6_DNS = "fd00:ad5:1e1d::2"
    }
}
