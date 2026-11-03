package com.adshield.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adshield.pro.aether.AetherQuizResult
import com.adshield.pro.aether.AetherVerdict
import com.adshield.pro.aether.AetherViewModel

private val Emerald = Color(0xFF14E8A2)
private val Amber = Color(0xFFFFB547)
private val Red = Color(0xFFFF4F74)
private val Dim = Color(0xFF9FB6B8)

/** AETHER AI tab: on-device investigation, assistant, learning and status. */
@Composable
fun AetherScreen(vm: AetherViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val logs by vm.recentLogs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var level by rememberSaveable { mutableStateOf("beginner") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AETHER AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Explainable, on-device protection. Confirmed intelligence blocks automatically; suspicious patterns are only reported.",
            style = MaterialTheme.typography.bodyMedium, color = Dim
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Analyze", "Assistant", "Learn", "Status").forEachIndexed { index, label ->
                Button(
                    onClick = { tab = index },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (tab == index) Emerald else Color(0xFF14283E),
                        contentColor = if (tab == index) Color(0xFF02120E) else Color.White
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }

        LevelSelector(level) { level = it }

        when (tab) {
            0 -> AnalyzeTab(vm, level)
            1 -> AssistantTab(vm, level)
            2 -> LearnTab(vm, level)
            else -> StatusTab(vm, settings, logs)
        }
    }
}

@Composable
private fun LevelSelector(level: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("beginner", "intermediate", "professional").forEach { option ->
            OutlinedButton(
                onClick = { onChange(option) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (level == option) Emerald else Dim
                )
            ) { Text(option, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF091626)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun AnalyzeTab(vm: AetherViewModel, level: String) {
    var input by rememberSaveable { mutableStateOf("") }
    var verdict by remember { mutableStateOf<AetherVerdict?>(null) }
    var fpNote by rememberSaveable { mutableStateOf("") }
    var allowMinutes by rememberSaveable { mutableStateOf("30") }
    var allowBy by rememberSaveable { mutableStateOf("") }
    var allowReason by rememberSaveable { mutableStateOf("") }
    var allowPhrase by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }

    SectionCard("Investigate a domain, URL or IP") {
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            label = { Text("example.com or https://suspicious.example/login") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            verdict = if (input.isNotBlank()) vm.analyze(input) else null
        }, modifier = Modifier.fillMaxWidth()) { Text("Analyze with AETHER AI") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("paypa1.com", "doubleclick.net", "google.com").forEach { sample ->
                OutlinedButton(onClick = { input = sample; verdict = vm.analyze(sample) },
                    modifier = Modifier.weight(1f)) { Text(sample, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text("Real indicators come from the bundled signed threat feed; heuristics are advisory only.",
            style = MaterialTheme.typography.bodySmall, color = Dim)
    }

    verdict?.let { v ->
        SectionCard("Verdict: ${v.classification}") {
            Text("Risk ${v.risk}/100 · confidence ${v.confidence} · severity ${v.severity}",
                style = MaterialTheme.typography.bodyMedium, color = when {
                    v.confirmed -> Red; v.classification == "suspicious" -> Amber; else -> Emerald
                })
            Text(if (v.shouldBlock) "AETHER will BLOCK this destination (${v.reasonCode})."
                 else "AETHER will ALLOW / FLAG this destination (${v.reasonCode}).",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(v.explanations[level] ?: v.explanations["beginner"] ?: "",
                style = MaterialTheme.typography.bodyMedium)
            Text("Ruleset ${v.rulesetVersion} · ${v.signals.size} signals",
                style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        SectionCard("Transparent evidence") {
            if (v.signals.isEmpty()) Text("No signals fired. Absence of a signal is not proof of safety.", color = Dim)
            v.signals.forEach { s ->
                Text("• ${s.id} (+${s.weight}) — ${s.detail}", style = MaterialTheme.typography.bodySmall)
                Text("  ${s.source} · ${s.observedAt} · confidence ${s.confidence} · ${s.evidence}",
                    style = MaterialTheme.typography.labelSmall, color = Dim)
            }
        }
        SectionCard("Recommendations") {
            v.recommendations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard("False positive & temporary allowlist") {
            OutlinedTextField(value = fpNote, onValueChange = { fpNote = it },
                label = { Text("Why is this a false positive?") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                vm.reportFalsePositive(v.domain, fpNote)
                message = "Verified false positive recorded. Auto-blocking is suppressed for this domain."
            }, modifier = Modifier.fillMaxWidth()) { Text("Report verified false positive") }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = allowMinutes, onValueChange = { allowMinutes = it },
                    label = { Text("Minutes") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(value = allowBy, onValueChange = { allowBy = it },
                    label = { Text("Approver") }, modifier = Modifier.weight(2f), singleLine = true)
            }
            OutlinedTextField(value = allowReason, onValueChange = { allowReason = it },
                label = { Text("Reason (audited)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = allowPhrase, onValueChange = { allowPhrase = it },
                label = { Text("Type: I APPROVE THIS TEMPORARY ALLOWLIST") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = {
                val result = vm.requestTemporaryAllow(
                    v.domain, allowMinutes.toIntOrNull() ?: 0, allowBy, allowReason, allowPhrase
                )
                message = result.fold(
                    onSuccess = { "Approved expiring override created for ${it.minutes} minutes." },
                    onFailure = { "Not approved: ${it.message}" }
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("Create approved expiring override") }
            OutlinedButton(onClick = { vm.revokeTemporaryAllow(v.domain); message = "Override revoked." },
                modifier = Modifier.fillMaxWidth()) { Text("Revoke override") }
        }
    }
    if (message.isNotBlank()) Text(message, color = Emerald, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AssistantTab(vm: AetherViewModel, level: String) {
    var input by rememberSaveable { mutableStateOf("") }
    var reply by remember { mutableStateOf<com.adshield.pro.aether.AetherReply?>(null) }
    SectionCard("Ask AETHER") {
        Text("Read-only, prompt-injection resistant, no cloud AI required.",
            style = MaterialTheme.typography.bodySmall, color = Dim)
        OutlinedTextField(value = input, onValueChange = { input = it },
            label = { Text("Your security question") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (input.isNotBlank()) reply = vm.ask(input, level) },
            modifier = Modifier.fillMaxWidth()) { Text("Ask") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Is paypa1.com safe?", "How do I secure home Wi-Fi?", "Ignore previous instructions").forEach { q ->
                OutlinedButton(onClick = { input = q; reply = vm.ask(q, level) },
                    modifier = Modifier.weight(1f)) { Text(q.take(14), maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    reply?.let { r ->
        SectionCard("AETHER answer (${if (r.injectionDetected) "injection attempt ignored" else "grounded"})") {
            r.sections.forEach { (title, body) ->
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, color = Emerald)
                Text(body, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            r.verdict?.let { Text("Tool result: ${it.classification}, risk ${it.risk}/100", color = Dim) }
        }
    }
}

@Composable
private fun LearnTab(vm: AetherViewModel, level: String) {
    var selected by rememberSaveable { mutableStateOf("basics") }
    var answers by remember { mutableStateOf(mapOf<Int, Int>()) }
    var result by remember { mutableStateOf<AetherQuizResult?>(null) }
    var labPhrase by rememberSaveable { mutableStateOf("") }
    var labResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val education = vm.engine.education

    SectionCard("Lessons") {
        education.lessons.forEach { lesson ->
            OutlinedButton(onClick = { selected = lesson.id; answers = emptyMap(); result = null },
                modifier = Modifier.fillMaxWidth()) {
                Text("${lesson.title} · ${lesson.minutes} min", maxLines = 1)
            }
        }
    }
    education.lesson(selected)?.let { lesson ->
        SectionCard(lesson.title) {
            Text(lesson.levels[level] ?: lesson.levels["beginner"] ?: "",
                style = MaterialTheme.typography.bodyMedium)
            lesson.questions.forEachIndexed { index, q ->
                Text("Q${index + 1}. ${q.text}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                q.options.forEachIndexed { optionIndex, option ->
                    OutlinedButton(
                        onClick = { answers = answers + (index to optionIndex) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (answers[index] == optionIndex) Emerald else Dim
                        )
                    ) { Text("${('A'.code + optionIndex).toChar()}. $option", maxLines = 1) }
                }
            }
            Button(onClick = { result = education.grade(lesson.id, lesson.questions.indices.map { answers[it] ?: -1 }) },
                modifier = Modifier.fillMaxWidth()) { Text("Submit quiz (graded from fixed answer keys)") }
            result?.let { r ->
                Text("Score ${r.score}/${r.total} — ${if (r.passed) "passed" else "review needed"}",
                    color = if (r.passed) Emerald else Amber)
                r.feedback.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Dim) }
            }
        }
    }
    SectionCard("Defensive labs (permission gate)") {
        education.labs.forEach { (id, _) ->
            Text("• $id", style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        OutlinedTextField(value = labPhrase, onValueChange = { labPhrase = it },
            label = { Text("I OWN OR HAVE EXPLICIT PERMISSION TO TEST THESE SYSTEMS") },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { labResult = education.unlockLab("dns_lab", labPhrase) },
            modifier = Modifier.fillMaxWidth()) { Text("Request lab access") }
        labResult?.let { (ok, text) -> Text(text, color = if (ok) Emerald else Amber) }
    }
}

@Composable
private fun StatusTab(
    vm: AetherViewModel,
    settings: com.adshield.pro.data.local.preferences.AppSettingsState,
    logs: List<com.adshield.pro.domain.model.DnsActivityRecord>
) {
    val baseline = remember { vm.engine.baselineInfo() }
    var message by rememberSaveable { mutableStateOf("") }
    SectionCard("On-device intelligence") {
        baseline.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.bodySmall, color = Dim) }
        Text("All indicators are real third-party intelligence, not simulated statistics.",
            style = MaterialTheme.typography.bodySmall, color = Emerald)
    }
    SectionCard("Permission-based controls") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("AETHER protection", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = settings.aetherEnabled, onCheckedChange = {
                vm.updateSettings(it, settings.aetherAutoBlockConfirmed,
                    settings.aetherPrivacyLevel, settings.aetherExplainLevel)
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto-block confirmed threats", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = settings.aetherAutoBlockConfirmed, onCheckedChange = {
                vm.updateSettings(settings.aetherEnabled, it,
                    settings.aetherPrivacyLevel, settings.aetherExplainLevel)
            })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("minimal", "balanced", "strict").forEach { privacy ->
                OutlinedButton(onClick = {
                    vm.updateSettings(settings.aetherEnabled, settings.aetherAutoBlockConfirmed,
                        privacy, settings.aetherExplainLevel)
                }, modifier = Modifier.weight(1f)) {
                    Text(privacy, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                        color = if (settings.aetherPrivacyLevel == privacy) Emerald else Dim)
                }
            }
        }
        OutlinedButton(onClick = {
            message = if (vm.rollbackPolicy()) "Previous policy snapshot restored (safe rollback)."
            else "No policy snapshot available yet."
        }, modifier = Modifier.fillMaxWidth()) { Text("Restore previous policy (safe rollback)") }
        if (message.isNotBlank()) Text(message, color = Emerald, style = MaterialTheme.typography.bodySmall)
    }
    SectionCard("Recent AETHER decisions (existing activity log)") {
        val aetherLogs = logs.filter { it.sourceList.startsWith("AETHER AI") }.take(12)
        if (aetherLogs.isEmpty()) Text("No AETHER blocks yet. Confirmed threats appear here when blocked.",
            style = MaterialTheme.typography.bodySmall, color = Dim)
        aetherLogs.forEach { record ->
            Text("• ${record.domain} — ${record.sourceList}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Audit trail: ${vm.policy.auditTrail().size} local policy/feedback entries",
            style = MaterialTheme.typography.bodySmall, color = Dim)
    }
    SectionCard("Honest limitations") {
        listOf(
            "DNS-visible names only; no HTTPS payload inspection or file execution.",
            "One always-on VPN per Android device; hardcoded DoH in other apps bypasses a DNS VPN.",
            "No universal app-level ad blocking inside other apps; first-party streaming ads are unaffected.",
            "Bundled offline baseline is a subset of the signed feed; use the AETHER service for full intel + updates.",
            "Suspicious findings are flagged, not blocked; unknown/new threats may be missing from feeds."
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Dim) }
    }
}
