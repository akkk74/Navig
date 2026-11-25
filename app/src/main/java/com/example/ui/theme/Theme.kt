package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Immutable
data class FleetExtendedColors(
    val glassSurface: Color,
    val glassBorder: Color,
    val glassDock: Color,
    val subtleSurface: Color,
    val mutedText: Color,
    val isDark: Boolean
)

val LocalFleetColors = staticCompositionLocalOf {
    FleetExtendedColors(
        glassSurface = Color(0xE8FFFFFF),
        glassBorder = Color(0xCCFFFFFF),
        glassDock = Color(0xEBFFFFFF),
        subtleSurface = Color(0xFFEAF0F9),
        mutedText = LightOnSurfaceMuted,
        isDark = false
    )
}

private val FleetDarkColorScheme = darkColorScheme(
    primary = KruCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0C3554),
    onPrimaryContainer = Color(0xFFBFE9FF),
    secondary = KruBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF172B4D),
    onSecondaryContainer = Color(0xFFBFDBFE),
    tertiary = ExcavatorAmber,
    onTertiary = Color(0xFF1E1400),
    background = DarkBgMid,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceMuted,
    outline = DarkOutline,
    error = LoadedHaulRed,
    onError = Color.White
)

private val FleetLightColorScheme = lightColorScheme(
    primary = KruBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = KruCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = ExcavatorOrange,
    onTertiary = Color.White,
    background = LightBgMid,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceMuted,
    outline = LightOutline,
    error = LoadedHaulRed,
    onError = Color.White
)

@Composable
fun KuzbassFleetTheme(
    darkTheme: Boolean = false,
    uiScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) FleetDarkColorScheme else FleetLightColorScheme
    val extendedColors = if (darkTheme) {
        FleetExtendedColors(
            glassSurface = Color(0xDE121C30),
            glassBorder = Color(0x2EFFFFFF),
            glassDock = Color(0xE610192B),
            subtleSurface = Color(0xFF1B2740),
            mutedText = DarkOnSurfaceMuted,
            isDark = true
        )
    } else {
        FleetExtendedColors(
            glassSurface = Color(0xEAFFFFFF),
            glassBorder = Color(0xE6FFFFFF),
            glassDock = Color(0xF0FFFFFF),
            subtleSurface = Color(0xFFF1F5F9),
            mutedText = LightOnSurfaceMuted,
            isDark = false
        )
    }

    val currentDensity = LocalDensity.current
    val clampedScale = uiScale.coerceIn(1f, 1.5f)
    val scaledDensity = Density(
        density = currentDensity.density,
        fontScale = currentDensity.fontScale * clampedScale
    )

    CompositionLocalProvider(
        LocalFleetColors provides extendedColors,
        LocalDensity provides scaledDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FleetTypography,
            content = content
        )
    }
}
