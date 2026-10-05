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
    primary = Color(0xFF4D94FF),
    onPrimary = Color.White,
    surface = Color(0xFF121212).copy(alpha = 0.55f),
    onSurface = Color(0xFFF2F4F7),
    onSurfaceVariant = Color(0xFF98A2B3),
    error = Color(0xFFFF6B61)
)

@Composable
fun NetControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
