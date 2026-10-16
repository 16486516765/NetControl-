package com.limao.netcontrol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.limao.netcontrol.ui.UiMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

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

/**
 * 主题：按界面风格分支（抄 KernelSU 的 KernelSUTheme 机制，简化版）。
 *
 * - MIUI：外层 MiuixTheme（液态玻璃底栏从它取色）+ 内层 MaterialTheme
 *   （现有 Material 3 组件继续工作，色值与 MIUI 的蓝保持一致）。
 *   不做 KSU 的 Monet / 调色板复杂逻辑，只跟 darkTheme + 主色。
 * - 谷歌：纯 MaterialTheme，与原来一致。
 */
@Composable
fun NetControlTheme(
    uiMode: UiMode = UiMode.Miuix,
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    when (uiMode) {
        UiMode.Miuix -> {
            val controller = ThemeController(
                if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
                keyColor = null,
                isDark = darkTheme,
                paletteStyle = ThemePaletteStyle.TonalSpot,
                colorSpec = ThemeColorSpec.Spec2025,
            )
            MiuixTheme(controller = controller) {
                MaterialTheme(
                    colorScheme = if (darkTheme) DarkColors else LightColors,
                    content = content
                )
            }
        }
        UiMode.Material -> {
            MaterialTheme(
                colorScheme = if (darkTheme) DarkColors else LightColors,
                content = content
            )
        }
    }
}
