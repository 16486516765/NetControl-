package com.limao.netcontrol.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy

/**
 * 真实 Liquid Glass 卡片（Kyant0/AndroidLiquidGlass Backdrop 2.0.1）。
 * 用 drawBackdrop 对全局背景做真实模糊 + 饱和度增强 + 高光/阴影。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val blurPx = with(density) { 28.dp.toPx() }
    val shape = RoundedCornerShape(cornerRadius)
    val clickableMod = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    // 真实玻璃：Backdrop 折射；降级时用半透明（无 Backdrop 的极端情况）
    val glassMod = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                blur(blurPx)
                vibrancy()
            }
        )
    } else {
        Modifier.background(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            shape
        )
    }

    Column(
        modifier = modifier
            .then(glassMod)
            .border(1.dp, Color.White.copy(alpha = 0.25f), shape)
            .then(clickableMod)
            .padding(16.dp),
        content = content
    )
}

/** Liquid Glass 风格开关：玻璃轨道 + 实心圆钮，带弹性位移动画。 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f, label = "toggleFraction"
    )
    val trackColor = lerp(
        Color(0xFF787880).copy(alpha = 0.35f),
        Color(0xFF34C759),
        fraction
    )

    Box(
        modifier = modifier
            .size(58.dp, 32.dp)
            .background(trackColor, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(start = 3.dp)
                .graphicsLayer {
                    translationX = fraction * with(density) { 26.dp.toPx() }
                }
                .size(26.dp)
                .background(Color.White, CircleShape)
                .border(0.5.dp, Color.Black.copy(alpha = 0.1f), CircleShape)
        )
    }
}

/** Liquid Glass 胶囊按钮。 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Color(0xFF0A84FF),
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "btnScale")

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(6.dp, CircleShape, clip = false)
            .background(accent.copy(alpha = if (enabled) 0.75f else 0.35f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 22.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Liquid Glass 搜索框（真实 Backdrop 折射）。 */
@Composable
fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索应用……"
) {
    val colors = MaterialTheme.colorScheme
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val blurPx = with(density) { 24.dp.toPx() }

    val glassMod = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                blur(blurPx)
                vibrancy()
            }
        )
    } else {
        Modifier.background(colors.surface.copy(alpha = 0.55f), CircleShape)
    }

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = modifier
            .fillMaxWidth()
            .then(glassMod)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clip(CircleShape)
            .height(52.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        decorationBox = { inner ->
            if (query.isEmpty()) {
                Text(
                    placeholder,
                    style = TextStyle(color = colors.onSurfaceVariant)
                )
            }
            inner()
        }
    )
}

/** 联网状态圆点 + 文字。 */
@Composable
fun StatusDot(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
