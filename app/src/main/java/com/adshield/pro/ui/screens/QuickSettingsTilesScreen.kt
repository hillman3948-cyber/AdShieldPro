package com.adshield.pro.ui.screens

import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adshield.pro.R
import com.adshield.pro.domain.model.PrivateDnsCycleStep
import com.adshield.pro.domain.model.PrivateDnsHostnameOption
import com.adshield.pro.domain.model.PrivateDnsMode
import com.adshield.pro.domain.model.PrivateDnsTileConfig
import com.adshield.pro.domain.model.SystemTilesLiveStatus
import com.adshield.pro.domain.model.UsbDebuggingTileConfig
import com.adshield.pro.engine.platform.SystemPreferencesController
import com.adshield.pro.engine.platform.SystemTileEngine
import com.adshield.pro.service.PrivateDnsTileService
import com.adshield.pro.service.UsbDebuggingTileService
import com.adshield.pro.ui.components.StatusPill
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun QuickSettingsTilesScreen(
    onBack: () -> Unit,
    status: SystemTilesLiveStatus,
    dnsConfig: PrivateDnsTileConfig,
    usbConfig: UsbDebuggingTileConfig,
    isAttemptingRootGrant: Boolean = false,
    onRefreshStatus: () -> Unit,
    onTryGrantWithRoot: () -> Unit = {},
    onApplyPrivateDnsStep: (PrivateDnsCycleStep) -> Unit,
    onCyclePrivateDnsNow: () -> Unit,
    onSetPrivateDnsIncludeOff: (Boolean) -> Unit,
    onSetPrivateDnsIncludeAuto: (Boolean) -> Unit,
    onSetPrivateDnsHostnameEnabled: (String, Boolean) -> Unit,
    onMovePrivateDnsHostname: (String, Int) -> Unit,
    onAddPrivateDnsHostname: (String, String, () -> Unit) -> Unit,
    onRemovePrivateDnsHostname: (String) -> Unit,
    onSetPrivateDnsRequireUnlock: (Boolean) -> Unit,
    onSetPrivateDnsAutoRevert: (Boolean, Int) -> Unit,
    onCancelPrivateDnsAutoRevert: () -> Unit,
    onSetUsbDebuggingEnabled: (Boolean) -> Unit,
    onSetUsbDebuggingAutoRevert: (Boolean, Int) -> Unit,
    onArmUsbDebuggingAutoRevertNow: () -> Unit,
    onCancelUsbDebuggingAutoRevert: () -> Unit,
    onSetUsbDebuggingRequireUnlock: (Boolean) -> Unit,
    onFeedback: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasActiveTimer = dnsConfig.autoRevertDeadlineEpochMs > nowEpochMs ||
        usbConfig.autoRevertDeadlineEpochMs > nowEpochMs

    LaunchedEffect(hasActiveTimer, dnsConfig.autoRevertDeadlineEpochMs, usbConfig.autoRevertDeadlineEpochMs) {
        nowEpochMs = System.currentTimeMillis()
        if (!hasActiveTimer) return@LaunchedEffect
        while (isActive) {
            delay(1_000L)
            val currentNow = System.currentTimeMillis()
            nowEpochMs = currentNow
            val dnsExpired = dnsConfig.autoRevertDeadlineEpochMs in 1..currentNow
            val usbExpired = usbConfig.autoRevertDeadlineEpochMs in 1..currentNow
            if (dnsExpired || usbExpired) {
                onRefreshStatus()
                break
            }
        }
    }

    // Poll every 2 seconds ONLY while permission setup is incomplete and this screen is visible,
    // so running `pm grant` via PC ADB, split-screen LADB/Termux, or Shizuku updates automatically.
    LaunchedEffect(status.hasWriteSecureSettingsPermission) {
        if (status.hasWriteSecureSettingsPermission) return@LaunchedEffect
        while (isActive) {
            delay(2_000L)
            onRefreshStatus()
        }
    }

    var newHostnameLabel by remember { mutableStateOf("") }
    var newHostnameValue by remember { mutableStateOf("") }
    var showSetupDetailsWhenReady by remember { mutableStateOf(false) }
    var showWirelessGuide by remember { mutableStateOf(false) }

    val adbCommand = remember(context.packageName) {
        SystemTileEngine.buildAdbGrantCommand(context.packageName)
    }
    val localShellCommand = remember(context.packageName) {
        SystemTileEngine.buildLocalShellGrantCommand(context.packageName)
    }
    val checklist = remember(status) {
        SystemTileEngine.evaluateQuickSetupChecklist(
            hasPermission = status.hasWriteSecureSettingsPermission,
            developerOptionsEnabled = status.usbDebuggingState.developerOptionsEnabled,
            hasSuBinary = status.isRootBinaryDetected,
            isShizukuInstalled = status.isShizukuInstalled
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
            }
            Text(
                text = "Quick Settings Tiles",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefreshStatus) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh live system preferences")
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Quick Setup Permission & Add Tiles Card
            item(key = "setup-card", contentType = "setup") {
                val showFullSetup = !status.hasWriteSecureSettingsPermission || showSetupDetailsWhenReady
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Quick Setup & Tile Permission",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            StatusPill(
                                text = if (status.hasWriteSecureSettingsPermission) "Granted" else "Setup Needed",
                                color = if (status.hasWriteSecureSettingsPermission) EmeraldPrimary else WarningAmber
                            )
                        }

                        if (status.hasWriteSecureSettingsPermission && !showSetupDetailsWhenReady) {
                            Text(
                                text = "WRITE_SECURE_SETTINGS is active. Both Quick Settings tiles can toggle Private DNS and USB Debugging directly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    OutlinedButton(
                                        onClick = {
                                            requestAddSystemTile(
                                                context = context,
                                                serviceClass = PrivateDnsTileService::class.java,
                                                labelRes = R.string.tile_private_dns_label,
                                                iconRes = R.drawable.ic_tile_private_dns,
                                                onMessage = onFeedback
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add Private DNS")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            requestAddSystemTile(
                                                context = context,
                                                serviceClass = UsbDebuggingTileService::class.java,
                                                labelRes = R.string.tile_usb_debugging_label,
                                                iconRes = R.drawable.ic_tile_usb_debugging,
                                                onMessage = onFeedback
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add USB Debug")
                                    }
                                }
                            }
                            TextButton(onClick = { showSetupDetailsWhenReady = true }) {
                                Text("Show permission commands & setup steps")
                            }
                        }

                        if (showFullSetup) {
                            if (status.hasWriteSecureSettingsPermission) {
                                TextButton(onClick = { showSetupDetailsWhenReady = false }) {
                                    Text("Collapse setup steps")
                                }
                            } else {
                                Text(
                                    text = "Complete these quick steps once to let AdShield Pro toggle system Private DNS and USB Debugging (auto-checks every 2s):",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Step 1: Developer Options
                            QuickSetupStepSurface(
                                stepNumber = "1",
                                title = "Developer Options",
                                done = checklist.developerOptionsReady,
                                detail = if (checklist.developerOptionsReady) {
                                    "Developer Options is enabled on this device."
                                } else {
                                    "Open About Phone and tap Build number 7 times to unlock Developer Options."
                                }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!checklist.developerOptionsReady) {
                                        OutlinedButton(
                                            onClick = { openAboutPhoneSettings(context, onFeedback) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Open About Phone")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { openDeveloperOptionsSettings(context, onFeedback) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Developer Options")
                                    }
                                }
                            }

                            // Step 2: Grant WRITE_SECURE_SETTINGS
                            QuickSetupStepSurface(
                                stepNumber = "2",
                                title = "Grant WRITE_SECURE_SETTINGS",
                                done = checklist.permissionGranted,
                                detail = if (checklist.permissionGranted) {
                                    "Permission granted and verified."
                                } else {
                                    "Choose your fastest method: 1-tap Root (if rooted), on-phone Wireless Debugging (LADB / Shizuku / Termux), or PC ADB."
                                }
                            ) {
                                if (checklist.rootQuickGrantAvailable) {
                                    Button(
                                        onClick = onTryGrantWithRoot,
                                        enabled = !isAttemptingRootGrant,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (isAttemptingRootGrant) "Requesting Root Grant..." else "1-Tap Grant via Root (su)")
                                    }
                                }

                                Text(
                                    text = "On-Phone (No PC) Shell Command:",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = localShellCommand,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            copyToClipboard(context, "AdShield pm grant command", localShellCommand)
                                            onFeedback("Copied shell command (for LADB / Termux / Shizuku).")
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy Shell")
                                    }
                                    if (status.isShizukuInstalled) {
                                        OutlinedButton(
                                            onClick = { launchHelperApp(context, SystemPreferencesController.PKG_SHIZUKU, "Shizuku", onFeedback) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Open Shizuku")
                                        }
                                    } else if (status.isLadbInstalled) {
                                        OutlinedButton(
                                            onClick = { launchHelperApp(context, SystemPreferencesController.PKG_LADB, "LADB", onFeedback) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Open LADB")
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { showWirelessGuide = !showWirelessGuide },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = if (showWirelessGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("No-PC Guide")
                                        }
                                    }
                                }

                                if (status.isShizukuInstalled || status.isLadbInstalled) {
                                    TextButton(onClick = { showWirelessGuide = !showWirelessGuide }) {
                                        Text(if (showWirelessGuide) "Hide Wireless Debugging steps" else "How to run via Wireless Debugging (No PC)")
                                    }
                                }

                                if (showWirelessGuide) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = CyberCyan.copy(alpha = 0.10f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("On-Device Wireless Debugging (Android 11+):", style = MaterialTheme.typography.labelLarge)
                                            Text("1. Tap Developer Options above and enable Wireless debugging.", style = MaterialTheme.typography.bodySmall)
                                            Text("2. Tap Wireless debugging -> Pair device with pairing code.", style = MaterialTheme.typography.bodySmall)
                                            Text("3. Pair in LADB (or start Shizuku and open Termux/aShell), then paste the copied shell command.", style = MaterialTheme.typography.bodySmall)
                                            Text("4. Return here — AdShield detects the grant automatically.", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }

                                Text(
                                    text = "From PC / Mac (USB ADB):",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = adbCommand,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            copyToClipboard(context, "AdShield ADB command", adbCommand)
                                            onFeedback("Copied ADB permission command.")
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Copy ADB")
                                    }
                                    Button(
                                        onClick = onRefreshStatus,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (status.hasWriteSecureSettingsPermission) Icons.Default.CheckCircle else Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (status.hasWriteSecureSettingsPermission) "Verified" else "Verify Now")
                                    }
                                }
                            }

                            // Step 3: Add Tiles to Quick Settings Panel
                            QuickSetupStepSurface(
                                stepNumber = "3",
                                title = "Add Tiles to Quick Settings",
                                done = false,
                                detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    "Tap below to pin each tile directly into your Android Quick Settings shade."
                                } else {
                                    "Pull down Quick Settings, tap Edit (pencil icon), and drag Private DNS & USB Debugging into active tiles."
                                }
                            ) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                requestAddSystemTile(
                                                    context = context,
                                                    serviceClass = PrivateDnsTileService::class.java,
                                                    labelRes = R.string.tile_private_dns_label,
                                                    iconRes = R.drawable.ic_tile_private_dns,
                                                    onMessage = onFeedback
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Add Private DNS")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                requestAddSystemTile(
                                                    context = context,
                                                    serviceClass = UsbDebuggingTileService::class.java,
                                                    labelRes = R.string.tile_usb_debugging_label,
                                                    iconRes = R.drawable.ic_tile_usb_debugging,
                                                    onMessage = onFeedback
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Add USB Debug")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Private DNS Tile Overview & Cycle Header Card
            item(key = "private-dns-header", contentType = "dns-header") {
                val activeText = when {
                    !status.privateDnsState.readable -> "Unreadable"
                    status.privateDnsState.mode == PrivateDnsMode.OFF -> "Off"
                    status.privateDnsState.mode == PrivateDnsMode.AUTO -> "Auto (Opportunistic)"
                    else -> status.privateDnsState.specifier ?: "Hostname"
                }
                val cycleSteps = SystemTileEngine.buildPrivateDnsCycle(dnsConfig)
                val nextStep = SystemTileEngine.nextPrivateDnsStep(status.privateDnsState, dnsConfig)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Dns, contentDescription = null, tint = CyberCyan)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Private DNS Tile", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "Current: $activeText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            StatusPill(
                                text = when {
                                    !status.privateDnsState.readable -> "Unreadable"
                                    status.privateDnsState.mode == PrivateDnsMode.OFF -> "Off"
                                    status.privateDnsState.mode == PrivateDnsMode.AUTO -> "Auto"
                                    else -> "Custom DoT"
                                },
                                color = if (status.privateDnsState.mode == PrivateDnsMode.OFF) WarningAmber else EmeraldPrimary
                            )
                        }

                        // Direct Quick-Apply controls
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = status.privateDnsState.mode == PrivateDnsMode.OFF,
                                onClick = { onApplyPrivateDnsStep(PrivateDnsCycleStep.Off) },
                                label = { Text("Off") },
                                enabled = status.hasWriteSecureSettingsPermission
                            )
                            FilterChip(
                                selected = status.privateDnsState.mode == PrivateDnsMode.AUTO,
                                onClick = { onApplyPrivateDnsStep(PrivateDnsCycleStep.Auto) },
                                label = { Text("Auto") },
                                enabled = status.hasWriteSecureSettingsPermission
                            )
                            Button(
                                onClick = onCyclePrivateDnsNow,
                                enabled = status.hasWriteSecureSettingsPermission
                            ) {
                                Text("Cycle -> ${stepLabel(nextStep)}")
                            }
                        }

                        HorizontalDivider()

                        Text(
                            text = "Modes Included When Tapping Tile (${cycleSteps.size} steps)",
                            style = MaterialTheme.typography.labelLarge
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Include \"Off\"", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Allows temporarily disabling system Private DNS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = dnsConfig.includeOffInCycle,
                                onCheckedChange = onSetPrivateDnsIncludeOff
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Include \"Auto\" (Opportunistic)", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Uses encrypted DNS when supported by network",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = dnsConfig.includeAutoInCycle,
                                onCheckedChange = onSetPrivateDnsIncludeAuto
                            )
                        }
                    }
                }
            }

            // 3. Keyed Hostname Cycle Items (lazy, stable keys, zero nested scroll)
            itemsIndexed(dnsConfig.hostnames, key = { _, item -> item.id }, contentType = { _, _ -> "dns-hostname" }) { index, item ->
                HostnameCycleRowCard(
                    option = item,
                    isCurrentlyActive = status.privateDnsState.mode == PrivateDnsMode.HOSTNAME &&
                        SystemTileEngine.normalizeHostname(status.privateDnsState.specifier.orEmpty()) ==
                        SystemTileEngine.normalizeHostname(item.hostname),
                    canApply = status.hasWriteSecureSettingsPermission,
                    canMoveUp = index > 0,
                    canMoveDown = index < dnsConfig.hostnames.lastIndex,
                    onToggleInCycle = { enabled -> onSetPrivateDnsHostnameEnabled(item.id, enabled) },
                    onMoveUp = { onMovePrivateDnsHostname(item.id, -1) },
                    onMoveDown = { onMovePrivateDnsHostname(item.id, 1) },
                    onApplyNow = {
                        onApplyPrivateDnsStep(PrivateDnsCycleStep.Hostname(item.label, item.hostname))
                    },
                    onRemove = { onRemovePrivateDnsHostname(item.id) }
                )
            }

            // 4. Add Custom Private DNS Hostname & Private DNS Auto-Revert Card
            item(key = "private-dns-footer", contentType = "dns-footer") {
                val hostnameCheck = remember(newHostnameValue) {
                    if (newHostnameValue.isBlank()) null
                    else SystemTileEngine.validatePrivateDnsHostname(newHostnameValue)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Add Custom DoT Hostname",
                            style = MaterialTheme.typography.titleMedium
                        )

                        OutlinedTextField(
                            value = newHostnameLabel,
                            onValueChange = { newHostnameLabel = it },
                            label = { Text("Display label (optional)") },
                            placeholder = { Text("e.g. Work NextDNS") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = newHostnameValue,
                            onValueChange = { newHostnameValue = it },
                            label = { Text("DNS-over-TLS hostname") },
                            placeholder = { Text("e.g. abc123.dns.nextdns.io") },
                            singleLine = true,
                            isError = hostnameCheck?.isFailure == true,
                            supportingText = {
                                val err = hostnameCheck?.exceptionOrNull()?.message
                                if (err != null) {
                                    Text(err)
                                } else {
                                    Text("Hostname only (no https://, ports, or raw IP addresses).")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                onAddPrivateDnsHostname(newHostnameLabel, newHostnameValue) {
                                    newHostnameLabel = ""
                                    newHostnameValue = ""
                                }
                            },
                            enabled = newHostnameValue.isNotBlank() && hostnameCheck?.isSuccess == true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Add Hostname to Tile Cycle")
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Require device unlock to toggle", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Prompts for unlock before switching Private DNS on lock screen",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = dnsConfig.requireUnlockToToggle,
                                onCheckedChange = onSetPrivateDnsRequireUnlock
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Auto-revert tile changes", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Restores the previous Private DNS mode after a temporary change",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = dnsConfig.autoRevertEnabled,
                                onCheckedChange = { enabled ->
                                    onSetPrivateDnsAutoRevert(enabled, dnsConfig.autoRevertDelayMinutes)
                                }
                            )
                        }

                        if (dnsConfig.autoRevertEnabled) {
                            AutoRevertDelayPicker(
                                selectedMinutes = dnsConfig.autoRevertDelayMinutes,
                                onSelectMinutes = { mins -> onSetPrivateDnsAutoRevert(true, mins) }
                            )
                        }

                        val dnsTarget = SystemTileEngine.privateDnsTargetStep(dnsConfig)
                        if (dnsConfig.autoRevertDeadlineEpochMs > nowEpochMs && dnsTarget != null) {
                            ActiveCountdownBanner(
                                message = "Reverting Private DNS to ${stepLabel(dnsTarget)} in ${formatDetailedCountdown(dnsConfig.autoRevertDeadlineEpochMs - nowEpochMs)}",
                                onCancel = onCancelPrivateDnsAutoRevert
                            )
                        }
                    }
                }
            }

            // 5. USB Debugging Quick Settings Tile Card
            item(key = "usb-debugging-card", contentType = "usb-debugging") {
                val usbState = status.usbDebuggingState
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.BugReport, contentDescription = null, tint = WarningAmber)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("USB Debugging Tile", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = when {
                                            !usbState.readable -> "Unreadable"
                                            usbState.enabled -> "Enabled (adb_enabled = 1)"
                                            else -> "Disabled (adb_enabled = 0)"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            StatusPill(
                                text = if (!usbState.readable) "Unreadable" else if (usbState.enabled) "On" else "Off",
                                color = if (usbState.enabled) WarningAmber else EmeraldPrimary
                            )
                        }

                        if (usbState.readable && !usbState.developerOptionsEnabled) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = WarningAmber.copy(alpha = 0.14f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Developer Options is Off in Android Settings. Android ignores turning USB Debugging On until Developer Options is enabled.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("USB Debugging", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Toggle ADB access directly from here or the Quick Settings tile",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = usbState.enabled,
                                onCheckedChange = onSetUsbDebuggingEnabled,
                                enabled = status.hasWriteSecureSettingsPermission
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Auto-revert back to Off", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Automatically disables USB Debugging after the delay expires",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = usbConfig.autoRevertEnabled,
                                onCheckedChange = { enabled ->
                                    onSetUsbDebuggingAutoRevert(enabled, usbConfig.autoRevertDelayMinutes)
                                }
                            )
                        }

                        if (usbConfig.autoRevertEnabled) {
                            AutoRevertDelayPicker(
                                selectedMinutes = usbConfig.autoRevertDelayMinutes,
                                onSelectMinutes = { mins -> onSetUsbDebuggingAutoRevert(true, mins) }
                            )
                        }

                        if (usbState.enabled && usbConfig.autoRevertDeadlineEpochMs <= nowEpochMs && status.hasWriteSecureSettingsPermission) {
                            OutlinedButton(
                                onClick = onArmUsbDebuggingAutoRevertNow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Turn Off Automatically in ${SystemTileEngine.clampDelayMinutes(usbConfig.autoRevertDelayMinutes)} min")
                            }
                        }

                        if (usbConfig.autoRevertDeadlineEpochMs > nowEpochMs && usbState.enabled) {
                            ActiveCountdownBanner(
                                message = "USB Debugging turns Off in ${formatDetailedCountdown(usbConfig.autoRevertDeadlineEpochMs - nowEpochMs)}",
                                onCancel = onCancelUsbDebuggingAutoRevert
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("Require device unlock to toggle", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "Recommended security guard when toggling ADB from the lock screen",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = usbConfig.requireUnlockToToggle,
                                onCheckedChange = onSetUsbDebuggingRequireUnlock
                            )
                        }
                    }
                }
            }

            item(key = "bottom-spacer", contentType = "spacer") {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HostnameCycleRowCard(
    option: PrivateDnsHostnameOption,
    isCurrentlyActive: Boolean,
    canApply: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggleInCycle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onApplyNow: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyActive) {
                EmeraldPrimary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = option.enabledInCycle,
                onCheckedChange = onToggleInCycle
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isCurrentlyActive) {
                        StatusPill(text = "Active", color = EmeraldPrimary)
                    }
                }
                Text(
                    text = option.hostname,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onApplyNow,
                enabled = canApply
            ) {
                Text("Use")
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.ExpandLess, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.ExpandMore, contentDescription = "Move down")
            }
            if (!option.isBuiltIn) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove custom hostname")
                }
            }
        }
    }
}

@Composable
private fun AutoRevertDelayPicker(
    selectedMinutes: Int,
    onSelectMinutes: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Auto-revert delay: ${SystemTileEngine.clampDelayMinutes(selectedMinutes)} min",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedMinutes == 1,
                onClick = { onSelectMinutes(1) },
                label = { Text("1m") }
            )
            SystemTileEngine.PRESET_REVERT_MINUTES.forEach { preset ->
                FilterChip(
                    selected = selectedMinutes == preset,
                    onClick = { onSelectMinutes(preset) },
                    label = { Text("${preset}m") }
                )
            }
            OutlinedButton(
                onClick = { onSelectMinutes(SystemTileEngine.clampDelayMinutes(selectedMinutes - 1)) },
                enabled = selectedMinutes > SystemTileEngine.MIN_REVERT_DELAY_MINUTES
            ) {
                Text("-1m")
            }
            OutlinedButton(
                onClick = { onSelectMinutes(SystemTileEngine.clampDelayMinutes(selectedMinutes + 5)) },
                enabled = selectedMinutes < SystemTileEngine.MAX_REVERT_DELAY_MINUTES
            ) {
                Text("+5m")
            }
        }
    }
}

@Composable
private fun ActiveCountdownBanner(
    message: String,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CyberCyan.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = CyberCyan,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

private fun stepLabel(step: PrivateDnsCycleStep): String = when (step) {
    PrivateDnsCycleStep.Off -> "Off"
    PrivateDnsCycleStep.Auto -> "Auto"
    is PrivateDnsCycleStep.Hostname -> step.label
}

private fun formatDetailedCountdown(remainingMs: Long): String {
    val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Composable
private fun QuickSetupStepSurface(
    stepNumber: String,
    title: String,
    done: Boolean,
    detail: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (done) {
            EmeraldPrimary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Step $stepNumber · $title",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (done) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

private fun openDeveloperOptionsSettings(context: Context, onFeedback: (String) -> Unit) {
    val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(devIntent) }.onFailure {
        runCatching { context.startActivity(fallback) }.onFailure {
            onFeedback("Open Android Settings -> Developer Options.")
        }
    }
}

private fun openAboutPhoneSettings(context: Context, onFeedback: (String) -> Unit) {
    val infoIntent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(infoIntent) }.onFailure {
        runCatching { context.startActivity(fallback) }.onFailure {
            onFeedback("Open Android Settings -> About Phone and tap Build number 7 times.")
        }
    }
}

private fun launchHelperApp(
    context: Context,
    packageName: String,
    appName: String,
    onFeedback: (String) -> Unit
) {
    val launch = context.packageManager.getLaunchIntentForPackage(packageName)
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }.onFailure {
            onFeedback("Unable to open $appName.")
        }
    } else {
        onFeedback("$appName is not installed on this device.")
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun requestAddSystemTile(
    context: Context,
    serviceClass: Class<*>,
    labelRes: Int,
    iconRes: Int,
    onMessage: (String) -> Unit
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
        if (statusBarManager == null) {
            onMessage("StatusBarManager is unavailable. Add the tile from Quick Settings Edit.")
            return
        }
        val component = ComponentName(context, serviceClass)
        val label = context.getString(labelRes)
        val icon = Icon.createWithResource(context, iconRes)
        statusBarManager.requestAddTileService(
            component,
            label,
            icon,
            context.mainExecutor
        ) { resultCode ->
            when (resultCode) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                    onMessage("$label tile added to Quick Settings.")
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                    onMessage("$label tile is already in Quick Settings.")
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                    onMessage("$label tile was not added.")
                else ->
                    onMessage("Add the tile from Quick Settings Edit (status $resultCode).")
            }
        }
    } else {
        onMessage("Pull down Quick Settings, tap Edit, and drag the tile into your active panel.")
    }
}
