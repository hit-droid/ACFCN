package com.selfmod.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SelfModColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentPurple,
    onSecondary = Color.White,
    tertiary = AccentGreen,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    error = Danger,
    onError = Color.White,
)

@Composable
fun SelfModTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SelfModColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
