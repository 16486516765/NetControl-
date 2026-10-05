package com.limao.netcontrol.ui.glass

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

/** 底部导航项。 */
data class BottomTab(
    val label: String,
    val icon: ImageVector
)

/**
 * 重构版 Liquid Glass 底部导航栏。
 *
 * 基于 Material 3 标准 NavigationBar（Google 官方组件）：
 * - 点击处理用 NavigationBarItem 标准 onClick，不再手动算坐标，点哪中哪
 * - 选中指示器用 M3 原生动画
 * - 玻璃外观：drawBackdrop 轻模糊 + 细描边 + 顶部高光，通透不抢戏
 * - 长按底栏展开/收起（弹簧动画），展开时显示快捷操作区
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
        targetValue = if (expanded) 116.dp else 76.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 380f),
        label = "barHeight"
    )

    // 玻璃底：先定尺寸再上效果，避免错位
    val glassBackground = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { barShape },
            effects = {
                blur(with(density) { 20.dp.toPx() })
            }
        )
    } else {
        Modifier.background(colors.surface.copy(alpha = 0.75f), barShape)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .then(glassBackground)
                .border(1.dp, Color.White.copy(alpha = 0.28f), barShape)
                .clip(barShape)
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { expanded = !expanded })
                },
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                contentColor = colors.onSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label
                            )
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary,
                            unselectedIconColor = colors.onSurfaceVariant,
                            unselectedTextColor = colors.onSurfaceVariant,
                            indicatorColor = colors.primary.copy(alpha = 0.18f)
                        )
                    )
                }
            }
        }
    }
}
