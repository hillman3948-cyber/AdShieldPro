package com.adshield.pro.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.util.Locale

/**
 * The active locale as an observable Compose value.
 *
 * `Locale.getDefault()` and the no-argument `NumberFormat.get*Instance()` helpers read a process
 * global that does not participate in recomposition, so numbers kept being formatted with the old
 * locale after the user changed the system language (Android Lint: `NonObservableLocale`).
 * Reading `LocalConfiguration` makes the calling composable re-run when the configuration changes.
 */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

/** Locale-aware integer grouping, e.g. `1,234` (en) or `1.234` (de). */
@Composable
@ReadOnlyComposable
fun formatInteger(value: Long): String = NumberFormat.getIntegerInstance(currentLocale()).format(value)

/** Locale-aware integer grouping for smaller counters. */
@Composable
@ReadOnlyComposable
fun formatInteger(value: Int): String = formatInteger(value.toLong())

/** Locale-aware percentage with one decimal, e.g. `12.5%` (en) or `%12,5` (tr). */
@Composable
@ReadOnlyComposable
fun formatPercent(fraction: Float): String = String.format(currentLocale(), "%.1f%%", fraction)
