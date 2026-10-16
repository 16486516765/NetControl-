package com.limao.netcontrol.ui.liquid

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limao.netcontrol.ui.glass.BottomTab
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * NetControl 的液态玻璃悬浮底栏（MIUI 模式专用）。
 *
 * 壳子是移植自 KSU 的 [FloatingBottomBar]（液态玻璃 + 弹簧选中指示器），
 * 这里只做 NetControl 的适配：三个 tab（应用 / 规则 / 设置）+ 底部间距。
 *
 * 调用方（MainActivity）只在 MIUI 模式下渲染它，谷歌模式仍用
 * [com.limao.netcontrol.ui.glass.GlassBottomBar]。
 */
@Composable
fun LiquidBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    isDark: Boolean,
    backdrop: Backdrop,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    FloatingBottomBar(
        modifier = modifier
            // 吞掉点按，避免点透到底层内容（照抄 KSU BottomBarMiuix）
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
        selectedIndex = selectedIndex,
        onSelected = onSelect,
        backdrop = backdrop,
        tabsCount = tabs.size,
        isBlurEnabled = true,
        isDark = isDark,
    ) { activateTab ->
        tabs.forEachIndexed { index, tab ->
            FloatingBottomBarItem(
                selected = selectedIndex == index,
                onClick = { activateTab(index) },
                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
            ) {
                // 图标与文字走 LocalContentColor，底栏 backdrop 副本会把它们
                // 重染成指示器内的强调色（与 KSU BottomBarMiuix 相同机制）。
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label
                )
                Text(
                    text = tab.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            }
        }
    }
}
