package com.limao.netcontrol.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 底部导航项。 */
data class BottomTab(
    val label: String,
    val icon: ImageVector
)

/**
 * Material 3 底部导航栏（深色，无玻璃拟态）。
 *
 * 纯黑背景上的深灰底栏，标准 NavigationBarItem，点击精准。
 */
@Composable
fun GlassBottomBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val barShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        NavigationBar(
            containerColor = Color(0xFF1C1C1E),
            contentColor = Color.White,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(barShape)
                .background(Color(0xFF1C1C1E), barShape)
        ) {
            tabs.forEachIndexed { index, tab ->
                NavigationBarItem(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(tab.icon, contentDescription = tab.label)
                    },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.primary,
                        selectedTextColor = colors.primary,
                        unselectedIconColor = Color(0xFF8E8E93),
                        unselectedTextColor = Color(0xFF8E8E93),
                        indicatorColor = colors.primary.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }
}
