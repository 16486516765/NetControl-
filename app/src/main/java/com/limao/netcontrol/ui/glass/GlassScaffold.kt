package com.limao.netcontrol.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

/** 全局玻璃背景（真实 Backdrop），供所有玻璃组件折射采样。 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 背景绘制逻辑：渐变 + 装饰光斑。Backdrop 与可见背景共用，保证折射内容一致。 */
private fun glassBackgroundDraw(darkTheme: Boolean): DrawScope.() -> Unit = {
    val colors = if (darkTheme) {
        listOf(Color(0xFF070B1D), Color(0xFF191238), Color(0xFF070B1D))
    } else {
        listOf(Color(0xFFDFEAFF), Color(0xFFF1E4FF), Color(0xFFDFF6FF))
    }
    drawRect(Brush.verticalGradient(colors))
    val minDim = size.minDimension
    drawCircle(
        Color(0xFF7C4DFF).copy(alpha = if (darkTheme) 0.35f else 0.25f),
        radius = minDim * 0.45f,
        center = Offset(size.width * 0.88f, size.height * 0.10f)
    )
    drawCircle(
        Color(0xFF00BCD4).copy(alpha = if (darkTheme) 0.30f else 0.22f),
        radius = minDim * 0.40f,
        center = Offset(size.width * 0.12f, size.height * 0.85f)
    )
    drawCircle(
        Color(0xFFFF6B9D).copy(alpha = if (darkTheme) 0.18f else 0.12f),
        radius = minDim * 0.30f,
        center = Offset(size.width * 0.50f, size.height * 0.55f)
    )
}

/**
 * 全 App 的 Liquid Glass 背景板（Kyant0/AndroidLiquidGlass Backdrop 2.0.1 真实实现）。
 *
 * 背景通过 rememberCanvasBackdrop 注册为 Backdrop，玻璃组件（GlassCard 等）
 * 使用 Modifier.drawBackdrop 对其做真实的模糊 + 折射 + 高光渲染。
 */
@Composable
fun GlassScaffold(
    darkTheme: Boolean,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = rememberCanvasBackdrop(glassBackgroundDraw(darkTheme))
    Box(Modifier.fillMaxSize()) {
        // 可见背景（与 Backdrop 绘制内容一致）
        Canvas(Modifier.fillMaxSize()) {
            glassBackgroundDraw(darkTheme)()
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            content()
        }
    }
}
