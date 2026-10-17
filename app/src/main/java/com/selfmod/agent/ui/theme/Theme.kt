package com.selfmod.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SelfModColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentBlue.copy(alpha = 0.22f),
    onPrimaryContainer = Color.White,
    secondary = AccentPurple,
    onSecondary = Color.White,
    tertiary = AccentGreen,
    onTertiary = Color.White,
    background = BgDark,
    onBackground = TextPrimary,
    surface = Color(0xF212131B),
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = AccentBlue,
    outline = Color(0x40FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Danger,
    onError = Color.White,
)

// Large iOS-style corner radii. These flow into M3 buttons, chips, text fields
// and cards via MaterialTheme.shapes.
private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun SelfModTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SelfModColors,
        typography = MaterialTheme.typography,
        shapes = GlassShapes,
        content = content,
    )
}
