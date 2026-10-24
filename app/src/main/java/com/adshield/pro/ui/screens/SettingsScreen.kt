package com.adshield.pro.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.data.local.preferences.DarkThemePreference
import com.adshield.pro.domain.model.AppExclusionInfo
import com.adshield.pro.domain.model.DnsProtocol
import com.adshield.pro.domain.model.DnsProviderPreset
import com.adshield.pro.domain.model.DnsResolverConfig
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settings: AppSettingsState,
    installedApps: List<AppExclusionInfo>,
    onUpdateResolverConfig: (DnsResolverConfig) -> Unit,
    onLoadInstalledApps: () -> Unit,
    onToggleAppExclusion: (String, Boolean) -> Unit,
    onToggleAutoStartBoot: (Boolean) -> Unit,
    onChangeThemePreference: (DarkThemePreference) -> Unit,
    onExportBackupJson: suspend () -> String,
    onRestoreBackupJson: (String) -> Unit
) {
    var showAppExclusionDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var backupJsonBuffer by remember { mutableStateOf("") }

    var customDohInput by remember(settings.resolverConfig) {
        mutableStateOf(settings.resolverConfig.customDohUrl)
    }
    var customDotInput by remember(settings.resolverConfig) {
        mutableStateOf(settings.resolverConfig.customDotHost)
    }
    var customIpv4Input by remember(settings.resolverConfig) {
        mutableStateOf(settings.resolverConfig.customPrimaryIpv4)
    }

    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Network, Apps & Privacy Settings",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Configure Encrypted DNS, Per-App VPN Exclusions, and Backup/Restore",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Encrypted DNS Protocol & Resolver Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = CyberCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Encrypted Upstream DNS Protocol",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    DnsProtocol.entries.forEach { protocol ->
                        val selected = settings.resolverConfig.protocol == protocol
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateResolverConfig(settings.resolverConfig.copy(protocol = protocol))
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    onUpdateResolverConfig(settings.resolverConfig.copy(protocol = protocol))
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = protocol.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = protocol.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Upstream DNS Provider (With Privacy Disclosure):",
                        style = MaterialTheme.typography.titleMedium
                    )

                    DnsProviderPreset.entries.forEach { preset ->
                        val selected = settings.resolverConfig.preset == preset
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) {
                                EmeraldPrimary.copy(alpha = 0.14f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateResolverConfig(settings.resolverConfig.copy(preset = preset))
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (selected) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = preset.primaryIpv4,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CyberCyan
                                    )
                                }
                                Text(
                                    text = preset.providerSummary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Privacy: ${preset.privacyDisclosure}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (settings.resolverConfig.preset == DnsProviderPreset.CUSTOM) {
                        OutlinedTextField(
                            value = customDohInput,
                            onValueChange = { customDohInput = it },
                            label = { Text("Custom DoH Endpoint (https://...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customDotInput,
                            onValueChange = { customDotInput = it },
                            label = { Text("Custom DoT Hostname (Port 853)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customIpv4Input,
                            onValueChange = { customIpv4Input = it },
                            label = { Text("Custom Primary IPv4 Address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onUpdateResolverConfig(
                                    settings.resolverConfig.copy(
                                        customDohUrl = customDohInput,
                                        customDotHost = customDotInput,
                                        customPrimaryIpv4 = customIpv4Input
                                    )
                                )
                            }
                        ) {
                            Text("Save Custom Resolver")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Text(
                                text = "IPv6 Dual-Stack DNS Routing",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Filters both IPv4 (A) and IPv6 (AAAA) DNS queries",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.resolverConfig.enableIpv6,
                            onCheckedChange = {
                                onUpdateResolverConfig(settings.resolverConfig.copy(enableIpv6 = it))
                            }
                        )
                    }
                }
            }
        }

        // Per-App VPN Controls & Streaming Compatibility Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Per-App VPN Controls (Split Tunneling)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "${settings.excludedAppPackages.size} app(s) currently excluded from VPN filtering",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Exclude specific banking, VoIP, or Android Auto applications if they require direct local LAN access. Also displays compatibility notes for streaming apps (YouTube, TikTok, Facebook).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onLoadInstalledApps()
                            showAppExclusionDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Manage Per-App VPN Exclusions")
                    }
                }
            }
        }

        // Background Lifecycle & Visual Theme Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Power, contentDescription = null, tint = EmeraldPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Auto-Restore Protection on Reboot",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Automatically resumes foreground VPN after device restart",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.autoStartOnBoot,
                            onCheckedChange = onToggleAutoStartBoot
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = CyberCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Interface Theme:",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DarkThemePreference.entries.forEach { pref ->
                            FilterChip(
                                selected = settings.darkThemeMode == pref,
                                onClick = { onChangeThemePreference(pref) },
                                label = {
                                    Text(
                                        text = pref.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Backup & Restore + Privacy Policy Disclosures Card
        item {
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
                        text = "Configuration Backup & Privacy Disclosures",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    backupJsonBuffer = onExportBackupJson()
                                    showBackupDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup / Restore")
                        }

                        OutlinedButton(
                            onClick = { showPrivacyPolicyDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Policy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Privacy Policy")
                        }
                    }
                }
            }
        }
    }

    if (showAppExclusionDialog) {
        var appSearch by remember { mutableStateOf("") }
        val filteredApps = remember(installedApps, appSearch) {
            installedApps.filter {
                appSearch.isBlank() ||
                    it.appName.contains(appSearch, ignoreCase = true) ||
                    it.packageName.contains(appSearch, ignoreCase = true)
            }
        }

        AlertDialog(
            onDismissRequest = { showAppExclusionDialog = false },
            title = { Text("Per-App VPN Exclusions") },
            text = {
                Column(modifier = Modifier.height(420.dp)) {
                    OutlinedTextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        label = { Text("Search installed apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = app.appName,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = app.packageName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = app.isExcludedFromVpn,
                                            onCheckedChange = { excluded ->
                                                onToggleAppExclusion(app.packageName, excluded)
                                            }
                                        )
                                    }

                                    if (app.compatibilityNote != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.Top) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = WarningAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = app.compatibilityNote,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = WarningAmber
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppExclusionDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Backup & Restore Configuration (JSON)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Copy the JSON below to save your custom rules, protection mode, and exclusions, or paste a saved JSON backup to restore:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = backupJsonBuffer,
                        onValueChange = { backupJsonBuffer = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRestoreBackupJson(backupJsonBuffer)
                        showBackupDialog = false
                    }
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restore JSON")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showPrivacyPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicyDialog = false },
            title = { Text("Privacy, Security & Google Play Data Safety") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "1. Local-Only VpnService Processing: AdShield Pro uses Android VpnService exclusively to create a local on-device DNS firewall. Non-DNS application traffic bypasses the TUN interface directly.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "2. Zero HTTPS Interception or Root Certificates: AdShield Pro never installs a CA certificate and never decrypts or inspects HTTPS payloads.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "3. Zero Browsing Data Collection or Sale: All DNS query logs and statistics are stored strictly inside your local SQLite database and can be disabled or wiped at any time.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "4. DNS Protocol Disclosure: Allowed DNS queries use your selected DoH, DoT, or unencrypted UDP/TCP resolver. If DoH or DoT fails, DNS returns SERVFAIL rather than silently switching to plaintext.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "5. Encrypted DNS Bypass Limitation: Applications that use hardcoded proprietary DoH/QUIC resolvers may bypass OS DNS routing unless configured to use system DNS.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyPolicyDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
