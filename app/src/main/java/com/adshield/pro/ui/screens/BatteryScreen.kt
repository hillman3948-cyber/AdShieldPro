package com.adshield.pro.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.engine.battery.BatteryDiagnostic
import com.adshield.pro.engine.battery.BatteryDiagnosticSeverity
import com.adshield.pro.engine.battery.BatteryDiagnostics
import com.adshield.pro.engine.battery.BatteryReading
import com.adshield.pro.engine.battery.BatteryReadingSource
import com.adshield.pro.engine.battery.BatterySession
import com.adshield.pro.engine.battery.BatteryTelemetry
import com.adshield.pro.ui.theme.ElectricBlue
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.SkyBlueGlow
import com.adshield.pro.ui.theme.WarningAmber
import java.text.DateFormat
import java.util.Date

/** Palette for the battery dashboard (reference: teal header, white metric card). */
private val BatteryTeal = Color(0xFF0E7C6B)
private val BatteryTealDeep = Color(0xFF0A5F52)
private val BatteryAmber = Color(0xFFE2701A)
private val CardSurface = Color(0xFF101C2C)
private val MutedText = Color(0xFF9FB6B8)

/**
 * AdShield Pro — Battery Intelligence dashboard.
 *
 * Every tile shows the value with the data source it came from (measured / computed / estimated /
 * not supported). Nothing is fabricated: when this device does not publish a value the tile says
 * so instead of showing a number.
 */
@Composable
fun BatteryScreen(
    telemetry: BatteryTelemetry,
    diagnostics: List<BatteryDiagnostic>,
    sessions: List<BatterySession>,
    history: List<BatteryDiagnostics.Sample>,
    healthScore: Int,
    chargeRatePercentPerHour: Float?,
    minutesToFull: Int?,
    minutesToEmpty: Int?,
    isDiagnosing: Boolean,
    lastDiagnosticAtMs: Long,
    onDetect: () -> Unit,
    onRefresh: () -> Unit,
    onClearSessions: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
    ) {
        item {
            BatteryHeader(
                telemetry = telemetry,
                isDiagnosing = isDiagnosing,
                minutesToFull = minutesToFull,
                minutesToEmpty = minutesToEmpty,
                onDetect = onDetect
            )
        }
        item {
            BatteryMetricCard(telemetry = telemetry, healthScore = healthScore)
        }
        if (history.size >= 2) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "Live trends",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    TrendChart(
                        title = "Battery level",
                        unit = "%",
                        color = EmeraldPrimary,
                        values = history.mapNotNull { it.levelPercent?.toFloat() }
                    )
                    TrendChart(
                        title = "Temperature",
                        unit = "°C",
                        color = BatteryAmber,
                        values = history.mapNotNull { it.temperatureCelsius }
                    )
                    TrendChart(
                        title = "Battery-side power",
                        unit = "W",
                        color = SkyBlueGlow,
                        values = history.mapNotNull { it.powerWatts }
                    )
                    TrendChart(
                        title = "Voltage",
                        unit = "V",
                        color = ElectricBlue,
                        values = history.mapNotNull { it.voltageVolts }
                    )
                    TrendChart(
                        title = "Current",
                        unit = "mA",
                        color = BatteryTeal,
                        values = history.mapNotNull { it.currentMilliAmps?.toFloat() }
                    )
                }
            }
        }
        item {
            DiagnosticsSection(
                diagnostics = diagnostics,
                lastDiagnosticAtMs = lastDiagnosticAtMs,
                chargeRatePercentPerHour = chargeRatePercentPerHour,
                isDiagnosing = isDiagnosing
            )
        }
        item {
            SessionsSection(sessions = sessions, onClearSessions = onClearSessions)
        }
        item {
            Surface(
                color = CardSurface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Where these numbers come from", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Level, temperature, voltage, health, technology and charging source come straight from " +
                            "Android's ACTION_BATTERY_CHANGED broadcast. Current and charge counters come from " +
                            "BatteryManager properties. Power is computed as voltage × measured current and is " +
                            "battery-side only — no app can read your charger's output. Charging speed is calculated " +
                            "from real level changes during this session. Values marked unsupported are simply not " +
                            "exposed by this device's firmware.",
                        fontSize = 12.sp,
                        color = MutedText
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Re-read hardware")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryHeader(
    telemetry: BatteryTelemetry,
    isDiagnosing: Boolean,
    minutesToFull: Int?,
    minutesToEmpty: Int?,
    onDetect: () -> Unit
) {
    val level = telemetry.levelPercent.rawValue
    Surface(color = BatteryTeal, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (telemetry.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = level?.let { "$it%" } ?: "—",
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${telemetry.statusLabel} · ${telemetry.pluggedLabel}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onDetect,
                enabled = !isDiagnosing,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = BatteryAmber,
                    disabledContainerColor = Color.White.copy(alpha = 0.6f),
                    disabledContentColor = BatteryAmber
                )
            ) {
                if (isDiagnosing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BatteryAmber)
                    Spacer(Modifier.width(8.dp))
                }
                Text("DETECT", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                HeaderMetric(
                    label = if (telemetry.isCharging) "Remaining time to full" else "Estimated runtime",
                    value = when {
                        telemetry.isCharging -> minutesToFull?.let { formatMinutes(it) } ?: "Measuring…"
                        else -> minutesToEmpty?.let { formatMinutes(it) } ?: "Measuring…"
                    },
                    sub = when {
                        telemetry.isCharging && minutesToFull == null -> "needs a few minutes of data"
                        !telemetry.isCharging && minutesToEmpty == null -> "needs a few minutes of data"
                        else -> "from measured rate"
                    }
                )
                HeaderMetric(
                    label = "Charging power",
                    value = telemetry.powerWatts.rawValue?.let { "${BatteryDiagnostics.format1(it)} W" } ?: "—",
                    sub = when (telemetry.powerWatts.source) {
                        BatteryReadingSource.COMPUTED -> "battery side"
                        BatteryReadingSource.UNSUPPORTED -> "not reported"
                        BatteryReadingSource.UNAVAILABLE -> "no reading yet"
                        else -> telemetry.powerWatts.label
                    }
                )
            }
        }
    }
}

@Composable
private fun HeaderMetric(label: String, value: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(160.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(sub, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BatteryMetricCard(telemetry: BatteryTelemetry, healthScore: Int) {
    Surface(color = CardSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            MetricRow(
                label = "Plugged in",
                value = telemetry.pluggedLabel,
                reading = null,
                valueColor = if (telemetry.pluggedRaw != 0) EmeraldPrimary else MutedText
            )
            MetricRow(
                label = "Temperature",
                value = telemetry.temperatureCelsius.rawValue?.let {
                    "${BatteryDiagnostics.format1(it)}°C / ${BatteryDiagnostics.format1(it * 9f / 5f + 32f)}°F"
                } ?: "Not supported",
                reading = telemetry.temperatureCelsius
            )
            MetricRow(
                label = "Health (Android status)",
                value = telemetry.healthLabel,
                reading = null,
                valueColor = when (telemetry.health) {
                    com.adshield.pro.engine.battery.BatteryHealthStatus.GOOD -> EmeraldPrimary
                    com.adshield.pro.engine.battery.BatteryHealthStatus.UNKNOWN -> MutedText
                    else -> WarningAmber
                }
            )
            MetricRow(
                label = "Battery condition score",
                value = "$healthScore/100 · ${BatteryDiagnostics.healthLabel(healthScore)}",
                reading = null,
                valueColor = if (healthScore >= 75) EmeraldPrimary else if (healthScore >= 60) WarningAmber else Color(0xFFFF6B6B),
                footnote = "Composite index from measured temperature, capacity counters and cycle count"
            )
            MetricRow(
                label = "Charging current",
                value = telemetry.currentMilliAmps.rawValue?.let { "$it mA" } ?: "Not supported",
                reading = telemetry.currentMilliAmps,
                valueColor = when {
                    telemetry.currentMilliAmps.rawValue == null -> MutedText
                    kotlin.math.abs(telemetry.currentMilliAmps.rawValue) < 400 && telemetry.isCharging -> BatteryAmber
                    else -> EmeraldPrimary
                },
                footnote = if (telemetry.currentMilliAmps.rawValue != null && telemetry.isCharging &&
                    kotlin.math.abs(telemetry.currentMilliAmps.rawValue) < 400
                ) "Below 400 mA while charging is usually slow charging" else null
            )
            MetricRow(
                label = "Average current",
                value = telemetry.currentAverageMilliAmps.rawValue?.let { "$it mA" } ?: "Not supported",
                reading = telemetry.currentAverageMilliAmps
            )
            MetricRow(
                label = "Voltage",
                value = telemetry.voltageVolts.rawValue?.let { "${BatteryDiagnostics.format1(it)} V" } ?: "Not supported",
                reading = telemetry.voltageVolts
            )
            MetricRow(
                label = "Charge counter",
                value = telemetry.chargeCounterMicroAmpHours.rawValue?.let { "${it / 1000} mAh" } ?: "Not supported",
                reading = telemetry.chargeCounterMicroAmpHours
            )
            MetricRow(
                label = "Design capacity",
                value = telemetry.chargeFullDesignMicroAmpHours.rawValue?.let { "${it / 1000} mAh" } ?: "Not supported",
                reading = telemetry.chargeFullDesignMicroAmpHours,
                footnote = "Android's nominal design figure, not a measurement of remaining capacity"
            )
            MetricRow(
                label = "Measured full capacity",
                value = telemetry.chargeFullMicroAmpHours.rawValue?.let { "${it / 1000} mAh" } ?: "Not supported",
                reading = telemetry.chargeFullMicroAmpHours
            )
            MetricRow(
                label = "Measured capacity health",
                value = telemetry.capacityHealthPercent?.let { "${BatteryDiagnostics.format1(it)}% of design" } ?: "Not measurable",
                reading = null,
                footnote = "Full-charge counter vs design capacity — reported only when both are exposed"
            )
            MetricRow(
                label = "Cycle count",
                value = telemetry.cycleCount.rawValue?.let { "$it cycles" } ?: "Not supported",
                reading = telemetry.cycleCount
            )
            MetricRow(
                label = "Technology",
                value = telemetry.technology,
                reading = null
            )
            MetricRow(
                label = "Device",
                value = "${telemetry.manufacturer} ${telemetry.model}",
                reading = null
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    reading: BatteryReading<*>?,
    valueColor: Color = Color.White,
    footnote: String? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MutedText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reading != null) {
                SourceBadge(reading.source)
                Spacer(Modifier.width(6.dp))
            }
            if (footnote != null) {
                Text(footnote, color = MutedText.copy(alpha = 0.75f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SourceBadge(source: BatteryReadingSource) {
    val (text, color) = when (source) {
        BatteryReadingSource.HARDWARE -> "MEASURED" to EmeraldPrimary
        BatteryReadingSource.COMPUTED -> "COMPUTED" to SkyBlueGlow
        BatteryReadingSource.ESTIMATED -> "ESTIMATED" to WarningAmber
        BatteryReadingSource.UNSUPPORTED -> "NOT SUPPORTED" to MutedText
        BatteryReadingSource.UNAVAILABLE -> "UNAVAILABLE" to MutedText
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun TrendChart(title: String, unit: String, color: Color, values: List<Float>) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max()
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    "${BatteryDiagnostics.format1(values.last())} $unit",
                    fontSize = 13.sp,
                    color = color,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                val range = (max - min).takeIf { it > 0.001f } ?: 1f
                val stepX = size.width / (values.size - 1).coerceAtLeast(1)
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = index * stepX
                    val y = size.height - ((value - min) / range) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = color, style = Stroke(width = 3f))
                drawCircle(
                    color = color,
                    radius = 5f,
                    center = Offset((values.size - 1) * stepX, size.height - ((values.last() - min) / range) * size.height)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("min ${BatteryDiagnostics.format1(min)} $unit", fontSize = 10.sp, color = MutedText)
                Text("max ${BatteryDiagnostics.format1(max)} $unit", fontSize = 10.sp, color = MutedText)
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(
    diagnostics: List<BatteryDiagnostic>,
    lastDiagnosticAtMs: Long,
    chargeRatePercentPerHour: Float?,
    isDiagnosing: Boolean
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ShowChart, contentDescription = null, tint = SkyBlueGlow, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("AI diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                isDiagnosing -> "Analyzing live readings…"
                lastDiagnosticAtMs == 0L -> "Tap DETECT to run a diagnostic against the live readings."
                else -> "Last run ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastDiagnosticAtMs))}" +
                    (chargeRatePercentPerHour?.let { " · measured ${BatteryDiagnostics.format1(it)}%/h" } ?: "")
            },
            fontSize = 11.sp,
            color = MutedText
        )
        Spacer(Modifier.height(8.dp))
        if (diagnostics.isEmpty()) {
            Text(
                "Diagnostics are rule-based and run entirely on-device. They explain only what the readings show — " +
                    "no faults are invented, and missing data is reported as missing.",
                fontSize = 12.sp,
                color = MutedText
            )
        }
        diagnostics.forEach { diagnostic ->
            DiagnosticCard(diagnostic)
        }
    }
}

@Composable
private fun DiagnosticCard(diagnostic: BatteryDiagnostic) {
    val (color, icon) = when (diagnostic.severity) {
        BatteryDiagnosticSeverity.CRITICAL -> Color(0xFFFF4F74) to Icons.Default.WarningAmber
        BatteryDiagnosticSeverity.WARNING -> BatteryAmber to Icons.Default.WarningAmber
        BatteryDiagnosticSeverity.NOTICE -> WarningAmber to Icons.Default.DeviceThermostat
        BatteryDiagnosticSeverity.INFO -> EmeraldPrimary to Icons.Default.Info
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(diagnostic.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(diagnostic.explanation, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(6.dp))
            Text("→ ${diagnostic.recommendation}", fontSize = 12.sp, color = color)
            Spacer(Modifier.height(6.dp))
            AssistChip(
                onClick = {},
                label = { Text("${diagnostic.confidenceLabel} · ${diagnostic.confidencePercent}%", fontSize = 10.sp) },
                colors = AssistChipDefaults.assistChipColors(labelColor = MutedText),
                modifier = Modifier.height(26.dp)
            )
            if (diagnostic.evidence.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                diagnostic.evidence.forEach {
                    Text("• $it", fontSize = 10.sp, color = MutedText.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun SessionsSection(sessions: List<BatterySession>, onClearSessions: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bolt, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Charging history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (sessions.isNotEmpty()) {
                TextButton(onClick = onClearSessions) { Text("Clear", fontSize = 12.sp) }
            }
        }
        if (sessions.isEmpty()) {
            Text(
                "Sessions are recorded automatically when you plug in or unplug. History stays on this device.",
                fontSize = 12.sp,
                color = MutedText
            )
            return
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions.take(20)) { session ->
                SessionCard(session)
            }
        }
    }
}

@Composable
private fun SessionCard(session: BatterySession) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        modifier = Modifier.width(180.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (session.wasCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                    contentDescription = null,
                    tint = if (session.wasCharging) EmeraldPrimary else MutedText,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (session.wasCharging) "Charging" else "On battery",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("${session.startLevelPercent}% → ${session.endLevelPercent}%", fontSize = 13.sp, color = EmeraldPrimary)
            Text(formatMinutes(session.durationMinutes), fontSize = 11.sp, color = MutedText)
            session.levelPercentPerHour?.let {
                Text("${BatteryDiagnostics.format1(it)}%/h measured", fontSize = 11.sp, color = MutedText)
            }
            session.peakPowerWatts?.let {
                Text("peak ${BatteryDiagnostics.format1(it)} W", fontSize = 11.sp, color = MutedText)
            }
            session.peakTemperatureCelsius?.let {
                Text("peak ${BatteryDiagnostics.format1(it)}°C", fontSize = 11.sp, color = MutedText)
            }
            Text(session.pluggedLabel, fontSize = 10.sp, color = MutedText.copy(alpha = 0.8f))
        }
    }
}

private fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours h" else "$hours h $rest min"
}
