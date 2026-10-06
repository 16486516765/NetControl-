package com.limao.netcontrol.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limao.netcontrol.data.AppInfo
import com.limao.netcontrol.data.NetworkRule
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.ui.AppIcon
import com.limao.netcontrol.ui.glass.GlassCard
import com.limao.netcontrol.ui.glass.LiquidButton
import com.limao.netcontrol.ui.glass.LiquidToggle
import com.limao.netcontrol.viewmodel.AppDetailViewModel
import com.limao.netcontrol.viewmodel.MainViewModel

@Composable
fun AppDetailScreen(
    packageName: String,
    cachedInfo: AppInfo?,
    cachedRule: NetworkRule?,
    detailViewModel: AppDetailViewModel,
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val info by detailViewModel.info.collectAsStateWithLifecycle()
    val draftBlocked by detailViewModel.draftBlocked.collectAsStateWithLifecycle()
    val draftWifi by detailViewModel.draftWifi.collectAsStateWithLifecycle()
    val draftMobile by detailViewModel.draftMobile.collectAsStateWithLifecycle()
    val applying by detailViewModel.applying.collectAsStateWithLifecycle()
    val applyError by detailViewModel.applyError.collectAsStateWithLifecycle()
    val applySuccess by detailViewModel.applySuccess.collectAsStateWithLifecycle()
    val splitSupported by mainViewModel.splitSupported.collectAsStateWithLifecycle()
    val privilege by mainViewModel.privilege.collectAsStateWithLifecycle()
    // 是否有操作权限：Root 可用或 Shizuku 已授权；无权限时禁用所有开关
    val hasPrivilege = when (val p = privilege) {
        is PrivilegeManager.Privilege.Root -> p.usable
        is PrivilegeManager.Privilege.Shizuku -> p.authorized
        else -> false
    }

    LaunchedEffect(packageName) {
        detailViewModel.clearApplyState()
        detailViewModel.load(packageName, cachedInfo)
        detailViewModel.setDraftFromRule(cachedRule)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "应用详情",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(8.dp))

        // 基本信息
        GlassCard(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AppIcon(packageName, size = 56.dp)
                Column {
                    Text(
                        info?.appName ?: packageName,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "UID: ${info?.uid ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 无权限时显示提示横幅
        if (!hasPrivilege) {
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "\u26A0\uFE0F",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "未授权：控制已禁用",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color(0xFFFF9F0A)
                        )
                        Text(
                            text = "请先完成 Shizuku 或 Root 授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 联网控制草稿
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "联网控制",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                label = "统一联网",
                sub = if (draftBlocked) "禁止" else "允许",
                checked = !draftBlocked,
                enabled = hasPrivilege && !applying,
                onChange = {
                    detailViewModel.updateDraft(!it, draftWifi, draftMobile)
                }
            )
            ToggleRow(
                label = "Wi-Fi",
                sub = if (draftWifi) "禁止" else "允许",
                checked = !draftWifi,
                enabled = hasPrivilege && !applying,
                onChange = {
                    detailViewModel.updateDraft(draftBlocked, !it, draftMobile)
                }
            )
            ToggleRow(
                label = "移动数据",
                sub = if (draftMobile) "禁止" else "允许",
                checked = !draftMobile,
                enabled = hasPrivilege && !applying,
                onChange = {
                    detailViewModel.updateDraft(draftBlocked, draftWifi, !it)
                }
            )
            if (splitSupported != true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "注：当前设备可能无法可靠区分 Wi-Fi 与移动数据，分开控制效果以实际为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        LiquidButton(
            onClick = { detailViewModel.applyNow() },
            enabled = hasPrivilege && !applying,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (applying) "应用中…" else "立即应用",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
        }

        if (applyError != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                applyError ?: "",
                color = Color(0xFFFF453A),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (applySuccess) {
            Spacer(Modifier.height(8.dp))
            Text(
                "规则已应用并写入系统",
                color = Color(0xFF34C759),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun ToggleRow(
    label: String,
    sub: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LiquidToggle(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
