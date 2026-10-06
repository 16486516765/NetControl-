package com.limao.netcontrol.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limao.netcontrol.data.AppInfo
import com.limao.netcontrol.ui.AppIcon
import com.limao.netcontrol.ui.glass.GlassCard
import com.limao.netcontrol.ui.glass.LiquidToggle
import com.limao.netcontrol.ui.glass.StatusDot
import com.limao.netcontrol.viewmodel.AppListViewModel

/** 规则页：所有被限制联网的应用。 */
@Composable
fun RulesScreen(
    listViewModel: AppListViewModel,
    onAppClick: (AppInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows by listViewModel.rows.collectAsStateWithLifecycle()
    val restricted = rows.filter { it.rule?.hasAnyRestriction == true }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            "规则",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
        Text(
            "共 ${restricted.size} 个应用被限制联网",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(Modifier.height(12.dp))
        if (restricted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无限制规则",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(restricted, key = { it.info.packageName }) { row ->
                    val rule = row.rule ?: return@items
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        cornerRadius = 18.dp,
                        onClick = { onAppClick(row.info) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AppIcon(row.info.packageName, size = 44.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    row.info.appName,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    row.info.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                val desc = buildString {
                                    if (rule.blocked) append("禁止联网")
                                    val parts = mutableListOf<String>()
                                    if (rule.wifiBlocked) parts += "Wi-Fi 被禁"
                                    if (rule.mobileBlocked) parts += "移动数据被禁"
                                    if (parts.isNotEmpty()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(parts.joinToString("、"))
                                    }
                                }
                                StatusDot(desc, Color(0xFFFF453A))
                            }
                            // 开 = 允许联网；关闭即解除全部限制
                            LiquidToggle(
                                checked = false,
                                onCheckedChange = {
                                    listViewModel.setAppBlocked(
                                        row.info.packageName, false
                                    )
                                },
                                enabled = !row.operating
                            )
                        }
                        if (row.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                row.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF453A)
                            )
                        }
                    }
                }
            }
        }
    }
}
