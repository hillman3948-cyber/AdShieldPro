package com.adshield.pro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.engine.platform.Capability
import com.adshield.pro.engine.platform.CapabilityArea
import com.adshield.pro.engine.platform.CapabilityRegistry
import com.adshield.pro.engine.platform.CapabilityStatus
import com.adshield.pro.engine.platform.DeviceProfile
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber

private val CardSurface = Color(0xFF101C2C)
private val MutedText = Color(0xFF9FB6B8)
private val StatusBlue = Color(0xFF2E7DFF)

/**
 * AdShield Pro — Status screen.
 *
 * Renders [CapabilityRegistry] verbatim: what works, what is partial, what is planned and what
 * Android simply does not allow. This screen is the app's own honesty contract — it is generated
 * from the same list a unit test validates.
 */
@Composable
fun CapabilitiesScreen(deviceProfile: DeviceProfile? = null) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (deviceProfile != null) {
            item { DeviceProfileCard(deviceProfile) }
        }
        item {
            Surface(color = CardSurface, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Capability status", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(CapabilityRegistry.summary(), fontSize = 13.sp, color = EmeraldPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Every row below is checked against the shipping code. A capability is only marked " +
                            "implemented when its code path exists in this build and is covered by the on-device " +
                            "or JVM test suite. Anything Android does not expose is listed as unavailable rather " +
                            "than faked.",
                        fontSize = 12.sp,
                        color = MutedText
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CountPill("Implemented", CapabilityRegistry.count(CapabilityStatus.IMPLEMENTED), EmeraldPrimary)
                        CountPill("Partial", CapabilityRegistry.count(CapabilityStatus.PARTIAL), WarningAmber)
                        CountPill("Planned", CapabilityRegistry.count(CapabilityStatus.PLANNED), StatusBlue)
                        CountPill("Unavailable", CapabilityRegistry.count(CapabilityStatus.UNAVAILABLE), MutedText)
                    }
                }
            }
        }
        CapabilityRegistry.byArea().forEach { (area, caps) ->
            item(key = "header-${area.name}") {
                Text(
                    area.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
            }
            items(caps, key = { it.id }) { capability ->
                CapabilityCard(capability)
            }
        }
    }
}

@Composable
private fun CountPill(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text("$count", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(label, fontSize = 9.sp, color = MutedText)
    }
}

@Composable
private fun CapabilityCard(capability: Capability) {
    val (statusColor, statusText) = when (capability.status) {
        CapabilityStatus.IMPLEMENTED -> EmeraldPrimary to "IMPLEMENTED"
        CapabilityStatus.PARTIAL -> WarningAmber to "PARTIAL"
        CapabilityStatus.PLANNED -> StatusBlue to "PLANNED"
        CapabilityStatus.UNAVAILABLE -> MutedText to "UNAVAILABLE"
    }
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(capability.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(statusText, fontSize = 9.sp, color = statusColor, fontWeight = FontWeight.Bold)
                }
            }
            if (capability.code.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(capability.code, fontSize = 10.sp, color = MutedText)
            }
            if (capability.permissions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Permissions: " + capability.permissions.joinToString { it.substringAfterLast('.') },
                    fontSize = 10.sp,
                    color = MutedText
                )
            }
            if (capability.limitations.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                capability.limitations.forEach {
                    Text("• $it", fontSize = 11.sp, color = MutedText.copy(alpha = 0.9f))
                }
            }
        }
    }
}

/** Everything this particular phone/tablet reports about itself, at a glance. */
@Composable
private fun DeviceProfileCard(profile: DeviceProfile) {
    Card(colors = CardDefaults.cardColors(containerColor = CardSurface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("This device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "${profile.manufacturer} ${profile.model} · Android ${profile.androidRelease} · ${profile.formFactor.name.lowercase().replace('_', ' ')}",
                fontSize = 13.sp,
                color = Color.White
            )
            Text(
                "CPU: ${profile.cpuArchitectures.joinToString(", ").ifEmpty { "not reported" }} — the same universal APK runs on every architecture",
                fontSize = 11.sp,
                color = MutedText
            )
            Text(
                "Platform support: ${if (profile.supportedPlatform) "within the tested range" else "outside the tested range (API ${profile.sdkInt})"}",
                fontSize = 11.sp,
                color = if (profile.supportedPlatform) EmeraldPrimary else WarningAmber
            )
            val radios = buildList {
                if (profile.hasWifi) add("Wi-Fi")
                if (profile.hasTelephony) add("Mobile data")
                if (profile.hasBluetooth) add("Bluetooth")
                if (profile.hasNfc) add("NFC")
                if (profile.hasGps) add("GPS")
                if (profile.hasTouchscreen) add("Touch")
            }
            if (radios.isNotEmpty()) {
                Text("Hardware: ${radios.joinToString(", ")}", fontSize = 11.sp, color = MutedText)
            }
            Text("Notifications: ${profile.notificationPermission}", fontSize = 11.sp, color = MutedText)

            Spacer(Modifier.height(10.dp))
            Text("Battery hardware on this device", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            profile.batteryChecks.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        if (row.supported) "✓" else "—",
                        color = if (row.supported) EmeraldPrimary else MutedText,
                        fontSize = 11.sp,
                        modifier = Modifier.width(18.dp)
                    )
                    Text(row.label, fontSize = 11.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Text(row.value, fontSize = 11.sp, color = if (row.supported) Color.White else MutedText)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Rows marked — are values this device's firmware does not publish to apps. They are shown as " +
                    "unavailable in the app rather than estimated.",
                fontSize = 10.sp,
                color = MutedText
            )
        }
    }
}
