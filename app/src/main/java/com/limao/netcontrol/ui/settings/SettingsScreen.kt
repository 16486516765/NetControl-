package com.limao.netcontrol.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.repository.SettingsRepository
import com.limao.netcontrol.ui.glass.GlassCard
import com.limao.netcontrol.ui.glass.LiquidButton
import com.limao.netcontrol.ui.glass.LiquidToggle
import com.limao.netcontrol.ui.glass.StatusDot
import com.limao.netcontrol.viewmodel.MainViewModel
import com.limao.netcontrol.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    settingsViewModel: SettingsViewModel,
    onRequestShizuku: () -> Unit,
    modifier: Modifier = Modifier
) {
    val privilege by settingsViewModel.privilege.collectAsStateWithLifecycle()
    val showSystem by settingsViewModel.showSystemApps.collectAsStateWithLifecycle(initialValue = false)
    val blockedOnly by settingsViewModel.showBlockedOnly.collectAsStateWithLifecycle(initialValue = false)
    val sortMode by settingsViewModel.sortMode.collectAsStateWithLifecycle(initialValue = "name")
    val bootRestore by settingsViewModel.bootRestore.collectAsStateWithLifecycle(initialValue = false)
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle(initialValue = "system")
    val pendingRestore by settingsViewModel.pendingRestore.collectAsStateWithLifecycle(initialValue = false)
    val customBgPath by settingsViewModel.customBackgroundPath.collectAsStateWithLifecycle(initialValue = "")
    val context = LocalContext.current
    var restoreMsg by remember { mutableStateOf<String?>(null) }

    // 自定义背景图选择器：从相册选图，复制到内部存储
    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val destFile = java.io.File(context.filesDir, "custom_background.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                settingsViewModel.setCustomBackgroundPath(destFile.absolutePath)
            } catch (e: Exception) {
                restoreMsg = "背景图设置失败：${e.message}"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
        )

        // 权限管理
        SettingSection("权限管理") {
            val (text, color) = when (val p = privilege) {
                is PrivilegeManager.Privilege.Detecting -> "检测中…" to Color.Gray
                is PrivilegeManager.Privilege.Root ->
                    if (p.usable) "Root · 已连接" to Color(0xFF34C759)
                    else "Root 权限未授予" to Color(0xFFFF9F0A)
                is PrivilegeManager.Privilege.Shizuku -> when {
                    !p.installed -> "未安装 Shizuku" to Color(0xFFFF9F0A)
                    !p.running -> "Shizuku 未运行" to Color(0xFFFF9F0A)
                    !p.authorized -> "等待授权" to Color(0xFFFF9F0A)
                    else -> "Shizuku · 已连接" to Color(0xFF34C759)
                }
                is PrivilegeManager.Privilege.None -> "未获得系统权限" to Color(0xFFFF453A)
            }
            StatusDot(text, color)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (privilege is PrivilegeManager.Privilege.Shizuku &&
                    !(privilege as PrivilegeManager.Privilege.Shizuku).authorized
                ) {
                    LiquidButton(onClick = onRequestShizuku) {
                        Text("请求 Shizuku 授权", color = Color.White)
                    }
                }
                LiquidButton(onClick = { settingsViewModel.redetectPrivilege() }) {
                    Text("重新检测权限", color = Color.White)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Root / Shizuku 权限仅用于执行 iptables 网络控制命令，不会上传任何信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 应用显示
        SettingSection("应用显示") {
            SettingToggle(
                "显示系统应用", showSystem,
                onChange = settingsViewModel::setShowSystemApps
            )
            SettingToggle(
                "仅显示被限制应用", blockedOnly,
                onChange = settingsViewModel::setShowBlockedOnly
            )
            Text(
                "排序方式",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip("名称", sortMode == SettingsRepository.SortMode.NAME) {
                    settingsViewModel.setSortMode(SettingsRepository.SortMode.NAME)
                }
                SortChip("包名", sortMode == SettingsRepository.SortMode.PACKAGE) {
                    settingsViewModel.setSortMode(SettingsRepository.SortMode.PACKAGE)
                }
                SortChip("状态", sortMode == SettingsRepository.SortMode.STATUS) {
                    settingsViewModel.setSortMode(SettingsRepository.SortMode.STATUS)
                }
            }
        }

        // 网络控制
        SettingSection("网络控制") {
            SettingToggle(
                "开机自动恢复规则", bootRestore,
                onChange = settingsViewModel::setBootRestore
            )
            Text(
                "设备开机后自动重新应用已保存的网络规则。部分设备后台限制严格，" +
                    "或 Shizuku 开机时尚未运行，可能导致恢复不可靠；" +
                    "此时 App 会标记“待恢复”并在下次启动时提示你手动恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pendingRestore) {
                Spacer(Modifier.height(8.dp))
                StatusDot("有规则待恢复", Color(0xFFFF9F0A))
                Spacer(Modifier.height(8.dp))
                LiquidButton(onClick = {
                    settingsViewModel.restorePendingRules { result ->
                        restoreMsg = result.fold(
                            onSuccess = { (ok, _) -> "已恢复 $ok 条规则" },
                            onFailure = { it.message ?: "恢复失败" }
                        )
                    }
                }) {
                    Text("立即恢复规则", color = Color.White)
                }
                restoreMsg?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "规则丢失检测：每次启动时自动对比系统实际规则与保存的规则，" +
                    "丢失则重新应用，绝不显示虚假状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 外观
        SettingSection("外观") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip("跟随系统", themeMode == SettingsRepository.ThemeMode.SYSTEM) {
                    settingsViewModel.setThemeMode(SettingsRepository.ThemeMode.SYSTEM)
                }
                SortChip("浅色", themeMode == SettingsRepository.ThemeMode.LIGHT) {
                    settingsViewModel.setThemeMode(SettingsRepository.ThemeMode.LIGHT)
                }
                SortChip("深色", themeMode == SettingsRepository.ThemeMode.DARK) {
                    settingsViewModel.setThemeMode(SettingsRepository.ThemeMode.DARK)
                }
            }
            Spacer(Modifier.height(12.dp))
            // 自定义背景图
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "自定义背景图",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (customBgPath.isNotEmpty()) "已设置自定义背景" else "从相册选择图片作为背景",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidButton(onClick = { bgPicker.launch("image/*") }) {
                        Text("选择图片", color = Color.White)
                    }
                    if (customBgPath.isNotEmpty()) {
                        LiquidButton(
                            onClick = {
                                settingsViewModel.setCustomBackgroundPath("")
                                try { java.io.File(customBgPath).delete() } catch (_: Exception) {}
                            },
                            accent = Color(0xFF787880)
                        ) {
                            Text("清除", color = Color.White)
                        }
                    }
                }
            }
        }

        // 关于
        SettingSection("关于") {
            AboutRow("应用名称", "青栅")
            AboutRow("版本号", "1.0.0 (1)")
            AboutRow("包名", "com.limao.netcontrol")
            AboutRow("UI 驱动", "Kyant0/AndroidLiquidGlass 2.0.1")
            AboutRow("网络控制", "iptables（Root / Shizuku）")
            Spacer(Modifier.height(4.dp))
            Text(
                "本地工具，无广告、无统计、无账号、不上传任何数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun SettingSection(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        content = {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    )
}

@Composable
private fun SettingToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        LiquidToggle(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SortChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(
        Modifier
            .clip(CircleShape)
            .background(
                if (selected) colors.primary.copy(alpha = 0.25f)
                else colors.surface.copy(alpha = 0.4f),
                CircleShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) colors.primary else colors.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
