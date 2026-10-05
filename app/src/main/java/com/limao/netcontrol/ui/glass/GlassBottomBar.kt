package com.limao.netcontrol.ui.glass

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

/** 底部导航项。 */
data class BottomTab(
    val label: String,
    val icon: ImageVector
)

/**
 * iOS26 式 Liquid Glass 底部导航栏。
 *
 * - 真实折射：lens() + 色散（chromaticAberration），边缘有 iOS26 式的 RGB 分光
 * - 液态选中：指示器以弹簧物理在标签间滑动，而非生硬切换
 * - 按压形变：手指按下的标签像液体一样被压下，松手弹簧弹回
 * - 长按展开：长按底栏任意位置，底栏以弹簧动画展开/收起
 */
@Composable
fun GlassBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val barShape = RoundedCornerShape(36.dp)

    // 长按展开 / 收起
    var expanded by remember { mutableStateOf(false) }
    val barHeight by animateDpAsState(
        targetValue = if (expanded) 116.dp else 68.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "barHeight"
    )

    // 选中指示器液态滑动（弹簧，带一点阻尼的果冻感）
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f),
        label = "tabIndicator"
    )

    // 当前按压的标签（按压形变用）
    var pressedIndex by remember { mutableIntStateOf(-1) }

    var barWidthPx by remember { mutableIntStateOf(0) }
    fun indexAt(x: Float): Int {
        if (barWidthPx <= 0 || tabs.isEmpty()) return -1
        val tabW = barWidthPx / tabs.size
        if (tabW <= 0) return -1
        return (x / tabW).toInt().coerceIn(0, tabs.size - 1)
    }

    // 真实玻璃：blur 打底 + lens 折射 + 边缘色散
    val glassMod = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { barShape },
            effects = {
                blur(with(density) { 24.dp.toPx() })
                // TODO: lens() 在部分机型上触发 Size is unspecified 崩溃（backdrop 库在 size 未就绪时调用 getCornerRadii），暂时禁用
                // lens(
                //     refractionHeight = with(density) { 18.dp.toPx() },
                //     refractionAmount = with(density) { 26.dp.toPx() },
                //     chromaticAberration = true
                // )
            }
        )
    } else {
        Modifier.background(colors.surface.copy(alpha = 0.60f), barShape)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .onSizeChanged { barWidthPx = it.width }
                .then(glassMod)
                .border(1.dp, Color.White.copy(alpha = 0.28f), barShape)
                .clip(barShape)
                .pointerInput(tabs.size) {
                    detectTapGestures(
                        onPress = { offset ->
                            pressedIndex = indexAt(offset.x)
                            tryAwaitRelease()
                            pressedIndex = -1
                        },
                        onTap = { offset ->
                            val i = indexAt(offset.x)
                            if (i >= 0) onSelect(i)
                        },
                        onLongPress = {
                            expanded = !expanded
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // 液态滑动的选中指示器
            if (barWidthPx > 0 && tabs.isNotEmpty()) {
                val tabW = barWidthPx / tabs.size
                Box(
                    Modifier
                        .offset { IntOffset((animatedIndex * tabW).toInt(), 0) }
                        .width(with(density) { tabW.toDp() })
                        .fillMaxHeight()
                        .padding(horizontal = 6.dp, vertical = 7.dp)
                        .background(
                            colors.primary.copy(alpha = 0.30f),
                            RoundedCornerShape(28.dp)
                        )
                )
            }
            // 标签
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, tab ->
                    LiquidTab(
                        tab = tab,
                        selected = index == selectedIndex,
                        pressed = pressedIndex == index,
                        showHint = expanded,
                        colors = colors
                    )
                }
            }
            // 展开时的顶部提示
            if (expanded) {
                Text(
                    "长按收起",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun RowScope.LiquidTab(
    tab: BottomTab,
    selected: Boolean,
    pressed: Boolean,
    showHint: Boolean,
    colors: androidx.compose.material3.ColorScheme
) {
    // 按压液态形变：压下 0.86，松手弹簧弹回（低阻尼带来果冻感）
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "tabPress"
    )
    // 选中图标轻微放大
    val selectedScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "tabSelected"
    )
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale * selectedScale
                scaleY = scale * selectedScale
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                modifier = Modifier.size(22.dp),
                tint = if (selected) colors.primary
                else colors.onSurfaceVariant
            )
            Text(
                tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) colors.primary
                else colors.onSurfaceVariant
            )
            if (showHint) {
                Text(
                    if (selected) "当前页面" else "点击切换",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
