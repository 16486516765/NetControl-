package com.limao.netcontrol.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

/** 底部导航项。 */
data class BottomTab(
    val label: String,
    val icon: ImageVector
)

/** Liquid Glass 底部导航栏（Kyant0/AndroidLiquidGlass Backdrop 2.0.1 真实折射）。 */
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
    val blurPx = with(density) { 28.dp.toPx() }

    val glassMod = if (backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                blur(blurPx)
            }
        )
    } else {
        Modifier.background(colors.surface.copy(alpha = 0.60f), CircleShape)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .then(glassMod)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clip(CircleShape)
            .height(68.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .clickable { onSelect(index) }
                        .background(
                            if (selected) colors.primary.copy(alpha = 0.22f)
                            else Color.Transparent,
                            CircleShape
                        )
                        .padding(vertical = 10.dp),
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
                    }
                }
            }
        }
}
