package com.adshield.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adshield.pro.data.local.preferences.DarkThemePreference

// Brand Colors — Deep Navy & Emerald Protection Palette
val DeepNavyBackground = Color(0xFF07101E)
val NavySurface = Color(0xFF0E1B30)
val NavySurfaceVariant = Color(0xFF152642)
val NavyBorder = Color(0xFF1E3558)

val EmeraldPrimary = Color(0xFF10B981)
val EmeraldGlow = Color(0xFF34D399)
val EmeraldDark = Color(0xFF059669)

val AlertRed = Color(0xFFEF4444)
val WarningAmber = Color(0xFFF59E0B)
val CyberCyan = Color(0xFF06B6D4)
val PurpleAccent = Color(0xFF8B5CF6)

// Security Center accents (phishing / threat-scan surfaces)
val ElectricBlue = Color(0xFF2E7DFF)
val SkyBlueGlow = Color(0xFF5AA2FF)

val TextPrimaryDark = Color(0xFFF8FAFC)
val TextSecondaryDark = Color(0xFF94A3B8)

// Elevation surfaces (Material 3 surface-container roles) so that Cards, Sheets and
// NavigationBar pick up the navy ladder instead of the framework's neutral default.
private val NavyContainerLowest = Color(0xFF050D18)
private val NavyContainerLow = Color(0xFF0A1524)
private val NavyContainer = Color(0xFF0E1B30)
private val NavyContainerHigh = Color(0xFF152642)
private val NavyContainerHighest = Color(0xFF1C3050)

private val SlateContainerLowest = Color(0xFFFFFFFF)
private val SlateContainerLow = Color(0xFFF8FAFC)
private val SlateContainer = Color(0xFFF1F5F9)
private val SlateContainerHigh = Color(0xFFE2E8F0)
private val SlateContainerHighest = Color(0xFFCBD5E1)

private val AdShieldDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = Color(0xFF022C22),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    inversePrimary = EmeraldDark,
    secondary = CyberCyan,
    onSecondary = Color(0xFF083344),
    secondaryContainer = Color(0xFF164E63),
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = PurpleAccent,
    onTertiary = Color(0xFF1E1B4B),
    tertiaryContainer = Color(0xFF4C1D95),
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = DeepNavyBackground,
    onBackground = TextPrimaryDark,
    surface = NavySurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = NavySurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    surfaceTint = EmeraldPrimary,
    surfaceDim = Color(0xFF040B14),
    surfaceBright = NavySurfaceVariant,
    surfaceContainerLowest = NavyContainerLowest,
    surfaceContainerLow = NavyContainerLow,
    surfaceContainer = NavyContainer,
    surfaceContainerHigh = NavyContainerHigh,
    surfaceContainerHighest = NavyContainerHighest,
    inverseSurface = TextPrimaryDark,
    inverseOnSurface = DeepNavyBackground,
    error = AlertRed,
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    outline = NavyBorder,
    outlineVariant = Color(0xFF16283F),
    scrim = Color(0xFF000000)
)

private val AdShieldLightColorScheme = lightColorScheme(
    primary = EmeraldDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    inversePrimary = EmeraldGlow,
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = EmeraldDark,
    surfaceDim = Color(0xFFE2E8F0),
    surfaceBright = Color.White,
    surfaceContainerLowest = SlateContainerLowest,
    surfaceContainerLow = SlateContainerLow,
    surfaceContainer = SlateContainer,
    surfaceContainerHigh = SlateContainerHigh,
    surfaceContainerHighest = SlateContainerHighest,
    inverseSurface = Color(0xFF0F172A),
    inverseOnSurface = Color(0xFFF8FAFC),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),
    scrim = Color(0xFF000000)
)

private val MaterialDefaults = Typography()

/**
 * Typography built on top of the Material 3 defaults so that styles the app does not override
 * (display*, headlineSmall, titleSmall, bodySmall, labelLarge, labelSmall) stay coherent instead
 * of silently falling back to a different font metric set.
 */
val AdShieldTypography = MaterialDefaults.copy(
    headlineLarge = MaterialDefaults.headlineLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = MaterialDefaults.headlineMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleLarge = MaterialDefaults.titleLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = MaterialDefaults.titleMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    bodyLarge = MaterialDefaults.bodyLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = MaterialDefaults.bodyMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = MaterialDefaults.labelMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = MaterialDefaults.labelLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    )
)

/** Rounded, modern corner treatment used by cards, sheets and dialogs. */
val AdShieldShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun AdShieldProTheme(
    themePreference: DarkThemePreference = DarkThemePreference.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themePreference) {
        DarkThemePreference.DARK -> true
        DarkThemePreference.LIGHT -> false
        DarkThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (useDark) AdShieldDarkColorScheme else AdShieldLightColorScheme,
        typography = AdShieldTypography,
        shapes = AdShieldShapes,
        content = content
    )
}
