package com.adshield.pro.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adshield.pro.domain.model.CustomRule
import com.adshield.pro.domain.model.FilterEvaluationResult
import com.adshield.pro.domain.model.RuleConflictReport
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.RuleType
import com.adshield.pro.ui.theme.AlertRed
import com.adshield.pro.ui.theme.CyberCyan
import com.adshield.pro.ui.theme.EmeraldPrimary
import com.adshield.pro.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun RulesScreen(
    rules: List<CustomRule>,
    conflicts: List<RuleConflictReport>,
    sandboxResult: FilterEvaluationResult?,
    onAddRule: (String, RuleType, RuleMatchType?, String) -> Unit,
    onUpdateRule: (CustomRule) -> Unit,
    onToggleRule: (CustomRule, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onDeduplicateRules: () -> Unit,
    onImportRulesText: (String) -> Unit,
    onExportRulesText: suspend () -> String,
    onTestDomainSandbox: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf<RuleType?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }
    var showImportExportDialog by remember { mutableStateOf(false) }
    var importExportBuffer by remember { mutableStateOf("") }
    var sandboxDomainInput by remember { mutableStateOf("doubleclick.net") }

    val coroutineScope = rememberCoroutineScope()

    val filteredRules = remember(rules, searchQuery, filterType) {
        rules.filter { r ->
            (filterType == null || r.ruleType == filterType) &&
                (searchQuery.isBlank() ||
                    r.normalizedPattern.contains(searchQuery.trim(), ignoreCase = true) ||
                    r.comment.contains(searchQuery.trim(), ignoreCase = true))
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Custom Rule Editor",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = "Changes take effect immediately—no restart needed.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Rule")
                }
            }
        }

        // Interactive Domain Sandbox Tester Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Science,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Live Rule Engine Diagnostic Tester",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = sandboxDomainInput,
                            onValueChange = { sandboxDomainInput = it },
                            label = { Text("Test any domain (e.g. ads.pubmatic.com)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { onTestDomainSandbox(sandboxDomainInput) }) {
                            Text("Evaluate")
                        }
                    }

                    if (sandboxResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = if (sandboxResult.isBlocked) {
                                AlertRed.copy(alpha = 0.16f)
                            } else {
                                EmeraldPrimary.copy(alpha = 0.16f)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (sandboxResult.isBlocked) {
                                        "BLOCKED • ${sandboxResult.domain}"
                                    } else {
                                        "ALLOWED • ${sandboxResult.domain}"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (sandboxResult.isBlocked) AlertRed else EmeraldPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Source: ${sandboxResult.sourceName} • Matched: ${sandboxResult.matchedRule ?: "None"} • Priority: ${sandboxResult.priority} • Lookup: ${sandboxResult.evaluationTimeMicros} µs",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Conflict & Duplicate Detection Report Card
        if (conflicts.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = WarningAmber.copy(alpha = 0.12f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = WarningAmber
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Rule Conflict & Duplicate Analyzer (${conflicts.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = WarningAmber
                                )
                            }

                            TextButton(onClick = onDeduplicateRules) {
                                Icon(
                                    imageVector = Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clean Dupes", maxLines = 1)
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        conflicts.take(4).forEach { report ->
                            Text(
                                text = "• [${report.conflictType.label}] ${report.explanation}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Search, Filter Chips & Import/Export Row
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Search custom rules or comments") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filterType == null,
                        onClick = { filterType = null },
                        label = { Text("All (${rules.size})", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterType == RuleType.BLOCK,
                        onClick = { filterType = RuleType.BLOCK },
                        label = { Text("Blocked", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = filterType == RuleType.ALLOW,
                        onClick = { filterType = RuleType.ALLOW },
                        label = { Text("Allowed", maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            importExportBuffer = onExportRulesText()
                            showImportExportDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import / Export Custom Rules")
                }
            }
        }

        if (filteredRules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "No Custom Rules Yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Add exact domains (ads.example.com), subdomain rules (||tracker.com^), wildcards (*.adserver.*), or allowlist exceptions (@@||trusted.com^).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredRules, key = { it.id }) { rule ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (rule.ruleType == RuleType.BLOCK) Icons.Default.Block else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (rule.ruleType == RuleType.BLOCK) AlertRed else EmeraldPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = rule.normalizedPattern,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${rule.ruleType.name} • ${rule.matchType.name} • Priority ${rule.priority}" +
                                        if (rule.comment.isNotBlank()) " • ${rule.comment}" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = rule.isEnabled,
                                onCheckedChange = { onToggleRule(rule, it) }
                            )
                            IconButton(onClick = { editingRule = rule }) {
                                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Rule")
                            }
                            IconButton(onClick = { onDeleteRule(rule.id) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Rule",
                                    tint = AlertRed
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog || editingRule != null) {
        val initialRule = editingRule
        var patternInput by remember(initialRule) { mutableStateOf(initialRule?.rawPattern ?: "") }
        var selectedRuleType by remember(initialRule) { mutableStateOf(initialRule?.ruleType ?: RuleType.BLOCK) }
        var selectedMatchType by remember(initialRule) { mutableStateOf(initialRule?.matchType ?: RuleMatchType.SUBDOMAIN) }
        var commentInput by remember(initialRule) { mutableStateOf(initialRule?.comment ?: "") }

        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                editingRule = null
            },
            title = { Text(if (initialRule == null) "Add Custom Rule" else "Edit Custom Rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = patternInput,
                        onValueChange = { patternInput = it },
                        label = { Text("Domain or Wildcard (e.g. ads.example.com or *.track.*)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Action:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedRuleType == RuleType.BLOCK,
                            onClick = { selectedRuleType = RuleType.BLOCK },
                            label = { Text("Block (Priority 90)") }
                        )
                        FilterChip(
                            selected = selectedRuleType == RuleType.ALLOW,
                            onClick = { selectedRuleType = RuleType.ALLOW },
                            label = { Text("Allow Exception (Priority 100)") }
                        )
                    }

                    Text("Matching Scope:", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RuleMatchType.entries.forEach { mt ->
                            FilterChip(
                                selected = selectedMatchType == mt,
                                onClick = { selectedMatchType = mt },
                                label = { Text(mt.name) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = commentInput,
                        onValueChange = { commentInput = it },
                        label = { Text("Optional Note / Comment") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (initialRule == null) {
                            onAddRule(patternInput, selectedRuleType, selectedMatchType, commentInput)
                        } else {
                            onUpdateRule(
                                initialRule.copy(
                                    rawPattern = patternInput,
                                    ruleType = selectedRuleType,
                                    matchType = selectedMatchType,
                                    comment = commentInput
                                )
                            )
                        }
                        showAddDialog = false
                        editingRule = null
                    }
                ) {
                    Text("Save & Apply")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        editingRule = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showImportExportDialog) {
        AlertDialog(
            onDismissRequest = { showImportExportDialog = false },
            title = { Text("Import / Export Custom Rules") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Copy exported rules below, or paste Adblock / domain lines to import:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = importExportBuffer,
                        onValueChange = { importExportBuffer = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onImportRulesText(importExportBuffer)
                        showImportExportDialog = false
                    }
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import Rules")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
