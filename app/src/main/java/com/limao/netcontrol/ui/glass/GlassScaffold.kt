package com.limao.netcontrol.ui.glass

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

/** 全局玻璃背景（真实 Backdrop），供所有玻璃组件折射采样。 */
val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 是否有自定义背景图（自定义背景下卡片需要加遮罩保证可读性）。 */
val LocalHasCustomBackground = staticCompositionLocalOf { false }

/**
 * 背景绘制逻辑。
 * - 有自定义背景图时：绘制图片（居中裁剪铺满），玻璃折射实时跟随图片内容。
 * - 无自定义背景时：默认渐变 + 装饰光斑。
 * Backdrop 与可见背景共用同一绘制逻辑，保证折射内容一致。
 */
private fun glassBackgroundDraw(
    darkTheme: Boolean,
    customImage: androidx.compose.ui.graphics.ImageBitmap?
): DrawScope.() -> Unit = {
    if (customImage != null) {
        // 居中裁剪铺满
        val imgW = customImage.width.toFloat()
        val imgH = customImage.height.toFloat()
        val scale = maxOf(size.width / imgW, size.height / imgH)
        val dw = imgW * scale
        val dh = imgH * scale
        val dx = (size.width - dw) / 2f
        val dy = (size.height - dh) / 2f
        drawImage(
            image = customImage,
            dstOffset = androidx.compose.ui.unit.IntOffset(dx.toInt(), dy.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt())
        )
        // 不在这里加遮罩，保持图片清晰；可读性由 GlassCard 的遮罩层保证
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
 * @param customBackgroundPath 自定义背景图路径（内部存储），空字符串表示使用默认背景。
 *
 * 背景通过 rememberCanvasBackdrop 注册为 Backdrop，玻璃组件（GlassCard 等）
 * 使用 Modifier.drawBackdrop 对其做真实的模糊 + 折射 + 高光渲染。
 */
@Composable
fun GlassScaffold(
    darkTheme: Boolean,
    customBackgroundPath: String = "",
    content: @Composable BoxScope.() -> Unit
) {
    // 加载自定义背景图（路径变化时重新加载）
    val customImage = remember(customBackgroundPath) {
        if (customBackgroundPath.isNotEmpty()) {
            try {
                BitmapFactory.decodeFile(customBackgroundPath)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val backdrop = rememberCanvasBackdrop(glassBackgroundDraw(darkTheme, customImage))
    Box(Modifier.fillMaxSize()) {
        // 可见背景（与 Backdrop 绘制内容一致）
        Canvas(Modifier.fillMaxSize()) {
            glassBackgroundDraw(darkTheme, customImage)()
        }
        CompositionLocalProvider(
            LocalGlassBackdrop provides backdrop,
            LocalHasCustomBackground provides customImage != null
        ) {
            content()
        }
    }
}
