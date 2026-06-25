package com.system.superiormonitor.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════
//  COLOR TOKENS
// ═══════════════════════════════════════════════════════════

// ── Primary Accent ──────────────────────────────────────
val AccentGreen = Color(0xFF268E63)

// ── Surfaces & Backgrounds ──────────────────────────────
val Background = Color(0xFF181A1F)
val OuterCardSurface = Color(0xFF25272B)
val InnerCardSurface = Color(0xFF2C2F33)
val DrawerBackground = Color(0xFF25272B)

// ── Status Colors ───────────────────────────────────────
val SuccessGreen = Color(0xFF268E63)
val ErrorRed = Color(0xFFFF453A)
val WarningAmber = Color(0xFFFF9F0A)

// ── Text ────────────────────────────────────────────────
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA0A0A0)

// ── Misc ────────────────────────────────────────────────
val DividerColor = Color(0xFF3A3C40)
val ToggleTrackInactive = Color(0xFF5C5E62)
val TopBarBackground = Color(0xFF181A1F)

// ═══════════════════════════════════════════════════════════
//  TYPOGRAPHY
// ═══════════════════════════════════════════════════════════

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.15).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

// ═══════════════════════════════════════════════════════════
//  THEME
// ═══════════════════════════════════════════════════════════

private val SuperiorDarkScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = Background,
    primaryContainer = AccentGreen,
    onPrimaryContainer = TextPrimary,
    secondary = AccentGreen,
    onSecondary = TextPrimary,
    tertiary = WarningAmber,
    onTertiary = Background,
    background = Background,
    onBackground = TextPrimary,
    surface = OuterCardSurface,
    onSurface = TextPrimary,
    surfaceVariant = InnerCardSurface,
    onSurfaceVariant = TextSecondary,
    outline = DividerColor,
    outlineVariant = DividerColor,
    error = ErrorRed,
    onError = TextPrimary,
)

@Composable
fun SuperiorMonitorTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SuperiorDarkScheme,
        typography = Typography,
        content = content
    )
}
