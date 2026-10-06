package com.limao.netcontrol.ui.glass

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.limao.netcontrol.ui.motion.pressScale

/**
 * Material 3 卡片（深色，纯黑背景上的深灰卡片）。
 * 替代原来的玻璃拟态 GlassCard。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // 按压状态源：pressScale 与 clickable 共用，保证按压反馈同步。
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier
                    .pressScale(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick
                    )
            } else Modifier
        ),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

/** Material 3 开关。 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

/** Material 3 按钮。 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        content()
    }
}

/** Material 3 搜索框。 */
@Composable
fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索应用..."
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

/** 状态圆点 + 文字。 */
@Composable
fun StatusDot(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.padding(4.dp)
        ) {
            drawCircle(color, radius = 6.dp.toPx())
        }
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
