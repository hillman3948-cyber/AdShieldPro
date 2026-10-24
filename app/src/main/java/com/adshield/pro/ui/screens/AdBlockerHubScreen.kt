package com.adshield.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adshield.pro.data.local.preferences.AppSettingsState
import com.adshield.pro.domain.model.CustomRule
import com.adshield.pro.domain.model.FilterEvaluationResult
import com.adshield.pro.domain.model.FilterListSource
import com.adshield.pro.domain.model.RuleCategory
import com.adshield.pro.domain.model.RuleConflictReport
import com.adshield.pro.domain.model.RuleMatchType
import com.adshield.pro.domain.model.RuleType
import com.adshield.pro.engine.browser.BrowserContentBlocker

@Composable
fun AdBlockerHubScreen(
    sources: List<FilterListSource>,
    rules: List<CustomRule>,
    conflicts: List<RuleConflictReport>,
    sandboxResult: FilterEvaluationResult?,
    settings: AppSettingsState,
    isUpdatingFilters: Boolean,
    browserContentBlocker: BrowserContentBlocker,
    onToggleSource: (FilterListSource, Boolean) -> Unit,
    onSyncAllNow: () -> Unit,
    onAddCustomSource: (String, String, RuleCategory) -> Unit,
    onDeleteCustomSource: (String) -> Unit,
    onUpdateAutoSyncSchedule: (Boolean, Int) -> Unit,
    onAddRule: (String, RuleType, RuleMatchType?, String) -> Unit,
    onUpdateRule: (CustomRule) -> Unit,
    onToggleRule: (CustomRule, Boolean) -> Unit,
    onDeleteRule: (Long) -> Unit,
    onDeduplicateRules: () -> Unit,
    onImportRulesText: (String) -> Unit,
    onExportRulesText: suspend () -> String,
    onTestDomainSandbox: (String) -> Unit,
    onToggleSiteException: (String, Boolean) -> Unit,
    onUpdateBrowserPrivacy: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onAddCustomCosmeticSelector: (String) -> Unit,
    onShowFeedback: (String) -> Unit
) {
    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Blocklists", maxLines = 1)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.Rule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Rules (${rules.size})", maxLines = 1)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedSubTab == 2,
                    onClick = { selectedSubTab = 2 },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Browser", maxLines = 1)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedSubTab) {
                0 -> FiltersScreen(
                    sources = sources,
                    settings = settings,
                    isUpdating = isUpdatingFilters,
                    onToggleSource = onToggleSource,
                    onSyncAllNow = onSyncAllNow,
                    onAddCustomSource = onAddCustomSource,
                    onDeleteCustomSource = onDeleteCustomSource,
                    onUpdateAutoSyncSchedule = onUpdateAutoSyncSchedule
                )
                1 -> RulesScreen(
                    rules = rules,
                    conflicts = conflicts,
                    sandboxResult = sandboxResult,
                    onAddRule = onAddRule,
                    onUpdateRule = onUpdateRule,
                    onToggleRule = onToggleRule,
                    onDeleteRule = onDeleteRule,
                    onDeduplicateRules = onDeduplicateRules,
                    onImportRulesText = onImportRulesText,
                    onExportRulesText = onExportRulesText,
                    onTestDomainSandbox = onTestDomainSandbox
                )
                else -> BrowserScreen(
                    settings = settings,
                    browserContentBlocker = browserContentBlocker,
                    onToggleSiteException = onToggleSiteException,
                    onUpdateBrowserPrivacy = onUpdateBrowserPrivacy,
                    onAddCustomCosmeticSelector = onAddCustomCosmeticSelector,
                    onShowFeedback = onShowFeedback
                )
            }
        }
    }
}
