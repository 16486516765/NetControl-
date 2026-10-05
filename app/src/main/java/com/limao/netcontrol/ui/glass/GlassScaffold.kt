package com.limao.netcontrol.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 纯黑背景脚手架（Material Design 风格，无玻璃拟态）。
 *
 * 纯黑 #000000 背景，内容直接铺上。简单可靠。
 */
@Composable
fun GlassScaffold(
    content: @Composable BoxScope.() -> Unit
) {
    // 纯黑背景，无视主题，永远黑
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        content()
    }
}
