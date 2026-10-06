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
 * 青栅统一动效参数。
 *
 * 设计原则：
 * - 优先使用 RenderThread 驱动的属性（透明度、位移、缩放），避免触发布局重组；
 * - 列表项变化用 LazyColumn 的 animateItem，由 Compose 内部走位移动画；
 * - 页面转场用 AnimatedContent 的淡入淡出，简单且开销小。
 */
object Motion {
    /** 标准缓动时长（毫秒）。 */
    const val DURATION_SHORT = 180

    /** 页面转场时长（毫秒）。 */
    const val DURATION_MEDIUM = 220

    /** 卡片按压缩放比例。 */
    const val PRESSED_SCALE = 0.96f
}

/**
 * 卡片按压缩放反馈。
 *
 * 通过 [interactionSource] 监听按压状态，用 spring 驱动缩放。
 * 缩放走 graphicsLayer，不会引起父布局重新测量。
 *
 * @param interactionSource 与 clickable 共用的按压状态源，保证反馈同步。
 * @param enabled 是否启用按压反馈。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) Motion.PRESSED_SCALE else 1f,
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
