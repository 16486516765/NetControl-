package com.limao.netcontrol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0066FF),
    onPrimary = Color.White,
    surface = Color.White.copy(alpha = 0.55f),
    onSurface = Color(0xFF101828),
    onSurfaceVariant = Color(0xFF475467),
    error = Color(0xFFD92D20)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceContainerLowest = Color(0xFF0C0C0E),
    outlineVariant = Color(0xFF3A3A3C),
    error = Color(0xFFFF453A)
)

@Composable
fun NetControlTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
