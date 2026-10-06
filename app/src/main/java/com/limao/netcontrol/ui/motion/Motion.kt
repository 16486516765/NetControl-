package com.limao.netcontrol.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 低开销动效工具。
 *
 * 核心原则：所有动画只驱动 RenderThread 属性（位移 / 缩放 / 透明度），
 * 动画状态只在 [graphicsLayer] 的绘制阶段读取，不触发重组 ——
 * 动画再丝滑也不吃 CPU，不掉帧，不额外耗电。
 */

/** 按压缩放的目标值：轻微收缩，给出"按下去"的触感反馈。 */
private const val PRESSED_SCALE = 0.96f

/**
 * 按压缩放：手指按下时缩到 96%，松开后弹簧回弹。
 *
 * 必须与 clickable 共用同一个 [interactionSource]，否则按压状态对不上：
 * ```
 * val interactionSource = remember { MutableInteractionSource() }
 * Modifier
 *     .pressScale(interactionSource)
 *     .clickable(interactionSource = interactionSource) { ... }
 * ```
 *
 * 实现细节：[animateFloatAsState] 的值只在 graphicsLayer block 内读取，
 * 该 block 运行在绘制阶段，因此动画全程零重组，完全由 RenderThread 驱动。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PRESSED_SCALE else 1f,
        animationSpec = spring(
            stiffness = 500f,
            dampingRatio = 0.65f
        ),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
