package com.limao.netcontrol.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

/** 全局玻璃背景（真实 Backdrop），供所有玻璃组件折射采样。 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 是否有自定义背景图（自定义背景下卡片需要加遮罩保证可读性）。 */
val LocalHasCustomBackground = staticCompositionLocalOf { false }

/**
 * 背景绘制逻辑：默认渐变 + 装饰光斑；若用户设置了自定义背景图，
 * 则居中裁剪绘制背景图并加一层遮罩保证文字可读。
 * Backdrop 与可见背景共用，保证折射内容一致。
 */
private fun glassBackgroundDraw(
    darkTheme: Boolean,
    bgImage: ImageBitmap?
): DrawScope.() -> Unit = {
    if (bgImage != null) {
        val imgW = bgImage.width.toFloat()
        val imgH = bgImage.height.toFloat()
        if (imgW > 0 && imgH > 0) {
            // center-crop：填满屏幕
            val scale = maxOf(size.width / imgW, size.height / imgH)
            val dstW = imgW * scale
            val dstH = imgH * scale
            drawImage(
                image = bgImage,
                dstOffset = IntOffset(
                    ((size.width - dstW) / 2f).toInt(),
                    ((size.height - dstH) / 2f).toInt()
                ),
                dstSize = IntSize(dstW.toInt(), dstH.toInt())
            )
        }
        // 遮罩：保证前景文字可读，深色模式更重
        drawRect(Color.Black.copy(alpha = if (darkTheme) 0.38f else 0.14f))
    } else {
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
}

/**
 * 全 App 的 Liquid Glass 背景板（Kyant0/AndroidLiquidGlass Backdrop 2.0.1 真实实现）。
 *
 * 背景通过 rememberCanvasBackdrop 注册为 Backdrop，玻璃组件（GlassCard 等）
 * 使用 Modifier.drawBackdrop 对其做真实的模糊 + 折射 + 高光渲染。
 * [backgroundImage] 为用户自定义背景（可空），更换时 Backdrop 自动重建。
 */
@Composable
fun GlassScaffold(
    darkTheme: Boolean,
    backgroundImage: ImageBitmap? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val drawBg: DrawScope.() -> Unit = remember(darkTheme, backgroundImage) {
        glassBackgroundDraw(darkTheme, backgroundImage)
    }
    // rememberCanvasBackdrop 内部 remember(onDraw)，lambda 变化时自动重建
    val backdrop = rememberCanvasBackdrop(drawBg)
    Box(Modifier.fillMaxSize()) {
        // 可见背景（与 Backdrop 绘制内容一致）
        Canvas(Modifier.fillMaxSize()) {
            drawBg()
        }
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalHasCustomBackground provides backgroundImage != null
        ) {
            content()
        }
    }
}
