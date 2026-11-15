package com.adshield.pro.engine.connectors

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.job.JobScheduler
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaDrm
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.adshield.pro.data.local.preferences.UserPreferencesRepository
import com.adshield.pro.domain.connectors.ConnectorStatus
import com.adshield.pro.domain.connectors.ProbeResult
import com.adshield.pro.engine.intelligence.FinancialTargetClassifier
import com.adshield.pro.engine.security.QrImageDecoder
import com.adshield.pro.engine.security.UrlThreatScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Second-wave on-device probes.
 *
 * Split from [NativeConnectorProbes] so the original file stays reviewable. Same rules apply:
 * every probe performs a real read, reports exactly what Android returned, and turns a permission
 * or visibility restriction into a visible limitation instead of a fake success.
 *
 * Nothing here reads personal content: no contacts, messages, clipboard text, notification text,
 * keystrokes, photos or location. Counts and package names only.
 */
@Singleton
class NativeProbeExpansion @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository
) {

    private val packageManager: PackageManager get() = context.packageManager

    // ------------------------------------------------------------------ financial (local rules)

    private val urlScanner = UrlThreatScanner()

    /** Runs the real deterministic URL rules over fixed, self-describing test inputs. */
    suspend fun finHeuristics(): ProbeResult {
        val paymentScam = urlScanner.scan("https://gcash-verify-login.example/account/confirm?otp=1")
        val clean = urlScanner.scan("https://www.example.com/articles/how-to-pay-a-bill")
        val works = paymentScam.findings.isNotEmpty() && paymentScam.riskScore >= 0
        return ProbeResult(
            available = works,
            status = if (works) ConnectorStatus.CONNECTED else ConnectorStatus.ERROR,
            summary = if (works) {
                "Local URL rules produced ${paymentScam.findings.size} finding(s) on a payment-scam shape"
            } else {
                "Local URL rules returned no findings"
            },
            details = listOf(
                "scam-shape score" to "${paymentScam.riskScore}/100 (${paymentScam.threatLevel})",
                "benign-shape score" to "${clean.riskScore}/100 (${clean.threatLevel})"
            ),
            restriction = "Heuristics can miss novel scams and can flag legitimate sites."
        )
    }

    /** Financial-target classification, including a lookalike and a non-financial control. */
    suspend fun typosquat(): ProbeResult {
        val lookalike = FinancialTargetClassifier.classify("https://paypa1-secure.example/verify")
        val control = FinancialTargetClassifier.classify("https://www.example.com/blog/post")
        val scanner = UrlThreatScanner().scan("https://paypa1-secure.example/verify")
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "Lookalike classified as financial=${lookalike.isFinancial}; control=${control.isFinancial}",
            details = listOf(
                "lookalike reason" to lookalike.reason,
                "lookalike findings" to scanner.findings.size.toString(),
                "control reason" to control.reason
            ),
            restriction = "Comparison uses an embedded brand list, not registration or certificate data."
        )
    }

    /** IDN/punycode inspection using the platform IDN implementation. */
    suspend fun idn(): ProbeResult {
        val ascii = runCatching { java.net.IDN.toASCII("xn--pypal-4ve.com") }.getOrNull().orEmpty()
        val unicode = runCatching { java.net.IDN.toUnicode(ascii) }.getOrNull().orEmpty()
        val scanner = UrlThreatScanner().scan("https://xn--pypal-4ve.com/login")
        val ok = ascii.isNotBlank() && unicode != ascii
        return ProbeResult(
            available = ok,
            status = if (ok) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = if (ok) "Punycode decoded to \"$unicode\"" else "IDN conversion unavailable",
            details = listOf(
                "punycode" to ascii,
                "unicode" to unicode,
                "scanner findings" to scanner.findings.size.toString()
            ),
            restriction = "Homograph detection reports the decoded form; it cannot judge intent."
        )
    }

    /** Brand-token matching on host and path. */
    suspend fun brand(): ProbeResult {
        val match = FinancialTargetClassifier.classify("https://maya-wallet-help.example/login")
        val control = FinancialTargetClassifier.classify("https://docs.example.org/manual")
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "Brand/payment token match=${match.isFinancial}, control=${control.isFinancial}",
            details = listOf(
                "matched token" to (match.matchedToken ?: "none"),
                "reason" to match.reason
            ),
            restriction = "A brand token in a URL is a warning signal, not proof of impersonation."
        )
    }

    /** Redirect-chain inspection on a URL that carries a nested destination. */
    suspend fun redirect(): ProbeResult {
        val nested = urlScanner.scan("https://short.example/r?url=https%3A%2F%2Fevil.example%2Flogin")
        val works = nested.findings.isNotEmpty()
        return ProbeResult(
            available = works,
            status = if (works) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = if (works) "Nested redirect parameter detected" else "No redirect finding produced",
            details = nested.findings.take(4).mapIndexed { index, finding -> "finding ${index + 1}" to finding },
            restriction = "The app never follows a redirect to inspect it; analysis is static."
        )
    }

    /** Analyses a QR payload (text) with the same rules as a pasted URL. */
    suspend fun qrPayload(): ProbeResult {
        val payload = "https://pay.example-checkout.top/scan?amount=2500&ref=qr"
        val verdict = urlScanner.scan(payload)
        val financial = FinancialTargetClassifier.classify(payload, verdict.findings)
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "QR payload analysed: ${verdict.threatLevel} ${verdict.riskScore}/100",
            details = listOf(
                "payment target" to financial.isFinancial.toString(),
                "findings" to verdict.findings.size.toString()
            ),
            restriction = "Payload text is analysed; nothing is opened or paid."
        )
    }

    /** Full encode → decode round trip of the local QR decoder. */
    suspend fun qrImage(): ProbeResult {
        val payload = "https://example.com/qr-selftest"
        val result = QrImageDecoder.selfTest(payload)
        return result.fold(
            onSuccess = { decoded ->
                ProbeResult(
                    available = true,
                    status = ConnectorStatus.CONNECTED,
                    summary = "QR encoder/decoder round trip OK (${decoded.format})",
                    details = listOf(
                        "payload" to decoded.text,
                        "decoder" to "ZXing core, on-device"
                    ),
                    restriction = "The user must select an image; no screen capture or gallery scan happens."
                )
            },
            onFailure = { error ->
                ProbeResult(
                    available = false,
                    status = ConnectorStatus.ERROR,
                    summary = "QR decoder self-test failed: ${error.message}",
                    restriction = "Without a working decoder the QR capability is not claimed."
                )
            }
        )
    }

    // ------------------------------------------------------------------ privacy

    suspend fun installerProvenance(): ProbeResult {
        val packages = installedPackages(limit = 80)
        var noInstaller = 0
        var stores = 0
        val sample = mutableListOf<Pair<String, String>>()
        packages.forEach { pkg ->
            val installer = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    packageManager.getInstallSourceInfo(pkg.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstallerPackageName(pkg.packageName)
                }
            }.getOrNull()
            if (installer.isNullOrBlank()) {
                noInstaller++
                if (sample.size < 5) sample += pkg.packageName to "sideloaded / unknown"
            } else {
                stores++
                if (sample.size < 5) sample += pkg.packageName to installer
            }
        }
        return ProbeResult(
            available = packages.isNotEmpty(),
            status = if (packages.isNotEmpty()) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = "${packages.size} app(s) checked · $stores with a known installer, $noInstaller sideloaded",
            details = sample,
            restriction = "Sideloaded is not malicious: it only means Android has no installer package."
        )
    }

    suspend fun appFlags(): ProbeResult {
        val packages = installedPackages(limit = 80)
        var debuggable = 0
        var allowBackup = 0
        packages.forEach { pkg ->
            val info = pkg.applicationInfo ?: return@forEach
            if (info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) debuggable++
            if (info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0) allowBackup++
        }
        return ProbeResult(
            available = packages.isNotEmpty(),
            status = ConnectorStatus.CONNECTED,
            summary = "${packages.size} app(s) · $debuggable debuggable · $allowBackup allow backup",
            details = listOf("debuggable" to debuggable.toString(), "allowBackup" to allowBackup.toString()),
            restriction = "Flags are declarations by each app; they do not prove data was exposed."
        )
    }

    suspend fun inputMethods(): ProbeResult {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "InputMethodManager unavailable")
        val methods = runCatching { manager.enabledInputMethodList }.getOrElse { emptyList() }
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "${methods.size} enabled keyboard(s)",
            details = methods.take(8).map { it.packageName to (it.loadLabel(packageManager).toString()) },
            restriction = "Only the installed/enabled keyboard list is read; keystrokes are never observed."
        )
    }

    suspend fun roleHolders(): ProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ProbeResult(false, ConnectorStatus.DEGRADED, "RoleManager requires Android 10+")
        }
        val manager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "RoleManager unavailable")
        val roles = listOf(
            RoleManager.ROLE_SMS to "SMS",
            RoleManager.ROLE_DIALER to "Dialer",
            RoleManager.ROLE_BROWSER to "Browser",
            RoleManager.ROLE_HOME to "Home"
        )
        val details: List<Pair<String, String>> = roles.mapNotNull { pair ->
            val role = pair.first
            val label = pair.second
            runCatching { manager.isRoleHeld(role) }.getOrNull()?.let { held ->
                label to if (held) "held by a user-chosen app" else "no holder"
            }
        }
        return ProbeResult(
            available = details.isNotEmpty(),
            status = if (details.isNotEmpty()) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = "Read ${details.size} default role(s)",
            details = details,
            restriction = "Only role membership is read; roles cannot be changed from this app."
        )
    }

    suspend fun deviceAdmin(): ProbeResult {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "DevicePolicyManager unavailable")
        val admins: List<android.content.ComponentName> =
            runCatching { manager.activeAdmins }.getOrNull() ?: emptyList()
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "${admins.size} active device administrator(s)",
            details = admins.take(8).map { it.packageName to it.className },
            restriction = "An active administrator can hold management powers granted by the user, not by this app."
        )
    }

    /** Android 11+ only; the caller checks Build.VERSION.SDK_INT before calling. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun isManagedProfile(manager: UserManager): Boolean = manager.isManagedProfile

    suspend fun workProfile(): ProbeResult {
        val manager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "UserManager unavailable")
        // UserManager.userCount needs MANAGE_USERS (privileged, non-grantable) and the no-argument
        // isManagedProfile flag only exists from Android 11, so both are handled explicitly instead
        // of being assumed. The call itself lives in a @RequiresApi helper: the annotation states
        // the requirement in a way lint can verify, where @Suppress on an expression it did not read.
        val managed: Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { isManagedProfile(manager) }.getOrNull()
        } else {
            null
        }
        return ProbeResult(
            available = managed != null,
            status = if (managed == null) ConnectorStatus.DEGRADED else ConnectorStatus.CONNECTED,
            summary = when (managed) {
                true -> "Running inside a managed/work profile"
                false -> "Running in a personal profile"
                null -> "Managed-profile state requires Android 11+"
            },
            details = listOf("managedProfile" to (managed?.toString() ?: "not readable on Android 10")),
            restriction = "Android does not expose the number of users to an ordinary app; the organisation controls policy on a managed device."
        )
    }

    suspend fun vpnState(): ProbeResult {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "ConnectivityManager unavailable")
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return ProbeResult(
            available = capabilities != null,
            status = if (capabilities == null) ConnectorStatus.DEGRADED else ConnectorStatus.CONNECTED,
            summary = if (vpn) "A VPN transport is active" else "No VPN transport on the active network",
            details = listOf(
                "vpnTransport" to vpn.toString(),
                "validated" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?.toString() ?: "unknown")
            ),
            restriction = "Android does not reveal which other app owns a VPN, nor what it inspects."
        )
    }

    // ------------------------------------------------------------------ network

    suspend fun privateDns(): ProbeResult {
        val resolver = context.contentResolver
        val mode = runCatching {
            Settings.Global.getString(resolver, "private_dns_mode")
        }.getOrNull()
        val specifier = runCatching {
            Settings.Global.getString(resolver, "private_dns_specifier")
        }.getOrNull()
        val available = !mode.isNullOrBlank()
        return ProbeResult(
            available = available,
            status = if (available) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = if (available) "Private DNS mode: $mode" else "Private DNS mode is not readable on this build",
            details = listOf(
                "mode" to (mode ?: "unknown"),
                "specifier" to (specifier?.ifBlank { "opportunistic" } ?: "unknown")
            ),
            restriction = "Some OEM builds restrict these Settings.Global reads; the existing DNS engine is unaffected."
        )
    }

    suspend fun trafficStats(): ProbeResult {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val available = rx != TrafficStats.UNSUPPORTED.toLong()
        return ProbeResult(
            available = available,
            status = if (available) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = if (available) "Interface counters: ${formatBytes(rx)} down, ${formatBytes(tx)} up" else "TrafficStats unsupported",
            details = listOf("rxBytes" to rx.toString(), "txBytes" to tx.toString()),
            restriction = "Device-wide totals only; per-app usage requires user-granted usage access."
        )
    }

    @Suppress("DEPRECATION")
    suspend fun wifiSecurity(): ProbeResult {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_WIFI_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ProbeResult(
                false, ConnectorStatus.DEGRADED, "ACCESS_WIFI_STATE not granted",
                restriction = "Wi-Fi security state is unavailable without this normal permission."
            )
        }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "WifiManager unavailable")
        val info = runCatching { manager.connectionInfo }.getOrNull()
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "Wi-Fi connection info unavailable")
        val security = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { info.currentSecurityType }.getOrNull()
        } else {
            null
        }
        val securityLabel = when (security) {
            0 -> "OPEN"
            1 -> "WEP"
            2 -> "PSK / WPA"
            3 -> "EAP"
            4 -> "SAE / WPA3"
            5 -> "OWE"
            6 -> "WAPI PSK"
            7 -> "WAPI CERT"
            else -> "not reported"
        }
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "Wi-Fi ${info.supplicantState} · security $securityLabel",
            details = listOf(
                "supplicantState" to info.supplicantState.toString(),
                "security" to securityLabel,
                "rssi" to "${info.rssi} dBm"
            ),
            restriction = "SSID requires location permission on modern Android; it is not read here."
        )
    }

    // ------------------------------------------------------------------ device

    suspend fun mediaDrm(): ProbeResult {
        val uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") // Widevine
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(uuid)
            val level = runCatching { drm.getPropertyString("securityLevel") }.getOrNull()
            val version = runCatching { drm.getPropertyString("version") }.getOrNull()
            ProbeResult(
                available = !level.isNullOrBlank(),
                status = if (!level.isNullOrBlank()) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
                summary = "Widevine security level: ${level ?: "not reported"}",
                details = listOf("securityLevel" to (level ?: "?"), "version" to (version ?: "?")),
                restriction = "Only DRM security level is read; no media or device identifiers."
            )
        } catch (error: Exception) {
            ProbeResult(
                available = false,
                status = ConnectorStatus.DEGRADED,
                summary = "MediaDrm unavailable: ${error.javaClass.simpleName}",
                restriction = "Some devices without Widevine cannot answer this."
            )
        } finally {
            runCatching { drm?.release() }
        }
    }

    suspend fun gles(): ProbeResult {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "ActivityManager unavailable")
        val info = runCatching { manager.deviceConfigurationInfo }.getOrNull()
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "Device configuration unavailable")
        val major = info.reqGlEsVersion shr 16
        val minor = info.reqGlEsVersion and 0xFFFF
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "OpenGL ES $major.$minor declared",
            details = listOf("reqGlEsVersion" to "0x${Integer.toHexString(info.reqGlEsVersion)}"),
            restriction = "Declared capability, not a benchmark."
        )
    }

    suspend fun storageVolumes(): ProbeResult {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "StorageManager unavailable")
        val volumes = runCatching { manager.storageVolumes }.getOrElse { emptyList() }
        val details = volumes.take(8).map { volume ->
            val description = runCatching { volume.getDescription(context) }.getOrNull() ?: "volume"
            val state = runCatching { volume.state }.getOrNull() ?: "unknown"
            description to "$state · uuid=${runCatching { volume.uuid }.getOrNull() ?: "none"}"
        }
        return ProbeResult(
            available = volumes.isNotEmpty(),
            status = if (volumes.isNotEmpty()) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = "${volumes.size} storage volume(s)",
            details = details,
            restriction = "Free-space detail for each volume stays on the existing storage screen; no files are listed."
        )
    }

    suspend fun cameraInventory(): ProbeResult {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "CameraManager unavailable")
        return try {
            val ids = manager.cameraIdList
            val details = ids.take(8).map { id ->
                val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                val label = when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                val flash = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                "camera $id" to "$label${if (flash) " · flash" else ""}"
            }
            ProbeResult(
                available = ids.isNotEmpty(),
                status = if (ids.isNotEmpty()) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
                summary = "${ids.size} camera(s) reported",
                details = details,
                restriction = "Inventory only: no camera is opened and no image is captured."
            )
        } catch (error: Exception) {
            ProbeResult(
                available = false,
                status = ConnectorStatus.DEGRADED,
                summary = "Camera inventory denied: ${error.javaClass.simpleName}",
                restriction = "Some builds restrict camera enumeration for non-system apps."
            )
        }
    }

    suspend fun batteryEnergy(): ProbeResult {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "BatteryManager unavailable")
        val energy = runCatching {
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        }.getOrNull()
        val current = runCatching {
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()
        val energyValid = energy != null && energy != Long.MIN_VALUE
        val currentValid = current != null && current != Long.MIN_VALUE
        return ProbeResult(
            available = energyValid || currentValid,
            status = if (energyValid || currentValid) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = when {
                energyValid -> "Energy counter: ${energy!! / 1_000} µWh remaining"
                currentValid -> "Current now: ${current!! / 1_000} mA"
                else -> "This device's fuel gauge does not expose energy counters"
            },
            details = listOf(
                "energyCounter" to (energy?.toString() ?: "unsupported"),
                "currentNow" to (current?.toString() ?: "unsupported")
            ),
            restriction = "OEMs return Long.MIN_VALUE when the property is unsupported."
        )
    }

    suspend fun timezoneLocale(): ProbeResult {
        val zone = TimeZone.getDefault()
        val locale = context.resources.configuration.locales.takeIf { it.size() > 0 }?.get(0)
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "${zone.id} · ${locale?.toLanguageTag() ?: "unknown locale"}",
            details = listOf(
                "timezone" to zone.id,
                "offsetMinutes" to (zone.getOffset(System.currentTimeMillis()) / 60_000).toString()
            ),
            restriction = "Configuration snapshot only."
        )
    }

    // ------------------------------------------------------------------ automation

    suspend fun jobScheduler(): ProbeResult {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "JobScheduler unavailable")
        val jobs = runCatching { scheduler.allPendingJobs }.getOrElse { emptyList() }
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "${jobs.size} pending job(s) owned by this app",
            details = jobs.take(5).map { it.id.toString() to (it.service.className) },
            restriction = "Only this app's jobs are visible to an ordinary app."
        )
    }

    suspend fun contentObserver(): ProbeResult {
        val uri = Settings.System.getUriFor(Settings.System.TIME_12_24)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = Unit
        }
        return try {
            context.contentResolver.registerContentObserver(uri, false, observer)
            ProbeResult(
                available = true,
                status = ConnectorStatus.CONNECTED,
                summary = "ContentObserver registered on a settings URI",
                details = listOf("uri" to uri.toString()),
                restriction = "The probe unregisters immediately and never reads or stores the setting value."
            )
        } catch (error: Exception) {
            ProbeResult(false, ConnectorStatus.DEGRADED, "ContentObserver failed: ${error.javaClass.simpleName}")
        } finally {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    suspend fun fileObserver(): ProbeResult {
        val dir = File(context.cacheDir, "intel-probe")
        runCatching { dir.mkdirs() }
        var observer: FileObserver? = null
        return try {
            val watcher = object : FileObserver(dir, FileObserver.CREATE) {
                override fun onEvent(event: Int, path: String?) = Unit
            }
            observer = watcher
            watcher.startWatching()
            ProbeResult(
                available = true,
                status = ConnectorStatus.CONNECTED,
                summary = "FileObserver watching the app cache directory",
                details = listOf("path" to dir.absolutePath, "mask" to "CREATE"),
                restriction = "Scoped storage means only this app's own directory can be watched."
            )
        } catch (error: Exception) {
            ProbeResult(
                available = false,
                status = ConnectorStatus.DEGRADED,
                summary = "FileObserver unavailable: ${error.javaClass.simpleName}"
            )
        } finally {
            runCatching { observer?.stopWatching() }
        }
    }

    suspend fun batteryOptimization(): ProbeResult {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "PowerManager unavailable")
        val exempt = runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = if (exempt) "Battery optimisations are ignored for this app" else "Battery optimisations apply to this app",
            details = listOf("exempt" to exempt.toString()),
            restriction = "Exemption needs an explicit user action in system settings; the app never asks silently."
        )
    }

    suspend fun notificationChannel(): ProbeResult {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "NotificationManager unavailable")
        val channel = runCatching { manager.getNotificationChannel("adshield_vpn_channel") }.getOrNull()
        return ProbeResult(
            available = channel != null,
            status = if (channel != null) ConnectorStatus.CONNECTED else ConnectorStatus.DEGRADED,
            summary = if (channel != null) {
                "Protection channel importance=${channel.importance}"
            } else {
                "Protection channel does not exist yet (created on first service start)"
            },
            details = listOf(
                "channel" to "adshield_vpn_channel",
                "importance" to (channel?.importance?.toString() ?: "n/a")
            ),
            restriction = "Android 13+ notification permission can still block delivery."
        )
    }

    suspend fun exactAlarms(): ProbeResult {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return ProbeResult(false, ConnectorStatus.DEGRADED, "AlarmManager unavailable")
        val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = if (canSchedule) "Exact alarms permitted" else "Exact alarms not permitted; inexact scheduling is used",
            details = listOf("canScheduleExactAlarms" to canSchedule.toString()),
            restriction = "Exact alarms need a special user grant on Android 12+."
        )
    }

    suspend fun dataStore(): ProbeResult {
        val snapshot = preferences.getSettingsSnapshot()
        return ProbeResult(
            available = true,
            status = ConnectorStatus.CONNECTED,
            summary = "DataStore read OK · scheduledScan=${snapshot.scheduledScanEnabled}",
            details = listOf(
                "scheduledScanEnabled" to snapshot.scheduledScanEnabled.toString(),
                "updateIntervalHours" to snapshot.updateIntervalHours.toString(),
                "logRetentionDays" to snapshot.logRetentionDays.toString()
            ),
            restriction = "Reads only this app's own settings; nothing is exported."
        )
    }

    // ------------------------------------------------------------------ helpers

    @Suppress("DEPRECATION")
    private fun installedPackages(limit: Int) =
        runCatching { packageManager.getInstalledPackages(0) }.getOrDefault(emptyList()).take(limit)

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_024 * 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
        bytes < 1_024L * 1_024 * 1_024 -> String.format(Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1_024.0 * 1_024 * 1_024))
    }
}
