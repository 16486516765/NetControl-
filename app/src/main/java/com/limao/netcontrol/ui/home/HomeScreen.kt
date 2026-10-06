package com.limao.netcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.stickyHeader
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.limao.netcontrol.data.AppInfo
import com.limao.netcontrol.platform.network.NetworkStatus
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.ui.AppIcon
import com.limao.netcontrol.ui.glass.GlassCard
import com.limao.netcontrol.ui.glass.GlassSearchBar
import com.limao.netcontrol.ui.glass.LiquidButton
import com.limao.netcontrol.ui.glass.LiquidToggle
import com.limao.netcontrol.ui.glass.StatusDot
import com.limao.netcontrol.viewmodel.AppListViewModel
import com.limao.netcontrol.viewmodel.AppRow
import com.limao.netcontrol.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    listViewModel: AppListViewModel,
    onAppClick: (AppInfo) -> Unit,
    onRequestShizuku: () -> Unit,
    modifier: Modifier = Modifier
) {
    val privilege by mainViewModel.privilege.collectAsStateWithLifecycle()
    // 是否有操作权限：Root 可用或 Shizuku 已授权；检测中/无权限时禁用开关
    val hasPrivilege = when (val p = privilege) {
        is PrivilegeManager.Privilege.Root -> p.usable
        is PrivilegeManager.Privilege.Shizuku -> p.authorized
        else -> false
    }
    val query by listViewModel.searchQuery.collectAsStateWithLifecycle()
    val rows by listViewModel.rows.collectAsStateWithLifecycle()
    val isLoading by listViewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by listViewModel.loadError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        listViewModel.load()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            "网络控制",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )

        PrivilegeCard(
            privilege = privilege,
            onRequestShizuku = onRequestShizuku,
            onRedetect = { mainViewModel.redetect() },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(Modifier.height(12.dp))

        // 无权限时显示提示横幅（检测中时不显示，避免闪烁）
        if (!hasPrivilege && privilege !is PrivilegeManager.Privilege.Detecting) {
            UnauthorizedBanner(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
        }

        Box(Modifier.weight(1f)) {
            if (isLoading && rows.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (loadError != null && rows.isEmpty()) {
                Text(
                    loadError ?: "加载失败",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 搜索框吸顶：与列表同一滚动容器，消除视觉割裂感
                    stickyHeader {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .padding(bottom = 2.dp)
                        ) {
                            GlassSearchBar(
                                query = query,
                                onQueryChange = listViewModel::setSearchQuery,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    items(rows, key = { it.info.packageName }) { row ->
                        AppRowCard(
                            row = row,
                            hasPrivilege = hasPrivilege,
                            modifier = Modifier.animateItem(),
                            onToggle = { allowed ->
                                listViewModel.setAppBlocked(row.info.packageName, !allowed)
                            },
                            onClick = { onAppClick(row.info) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivilegeCard(
    privilege: PrivilegeManager.Privilege,
    onRequestShizuku: () -> Unit,
    onRedetect: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Text(
            "系统权限",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        when (privilege) {
            is PrivilegeManager.Privilege.Detecting -> {
                StatusDot("检测中…", Color.Gray)
            }
            is PrivilegeManager.Privilege.Root -> {
                if (privilege.usable) {
                    StatusDot("Root · 已连接", Color(0xFF34C759))
                    Text(
                        "网络控制正常",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    StatusDot("Root 权限未授予", Color(0xFFFF9F0A))
                }
            }
            is PrivilegeManager.Privilege.Shizuku -> {
                when {
                    !privilege.installed -> {
                        StatusDot("未安装 Shizuku", Color(0xFFFF9F0A))
                        Text(
                            "请安装 Shizuku 后再使用网络控制功能",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !privilege.running -> {
                        StatusDot("Shizuku 未运行", Color(0xFFFF9F0A))
                        Text(
                            "请先启动 Shizuku",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !privilege.authorized -> {
                        StatusDot("等待授权", Color(0xFFFF9F0A))
                        Spacer(Modifier.height(8.dp))
                        LiquidButton(onClick = onRequestShizuku) {
                            Text("请求 Shizuku 授权", color = Color.White)
                        }
                    }
                    else -> {
                        StatusDot("Shizuku · 已连接", Color(0xFF34C759))
                        Text(
                            "网络控制正常",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            is PrivilegeManager.Privilege.None -> {
                StatusDot("未获得系统权限", Color(0xFFFF453A))
                Text(
                    "需要 Root 或 Shizuku 才能控制应用联网，仅可查看应用列表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LiquidButton(onClick = onRedetect) {
            Text("重新检测", color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UnauthorizedBanner(modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 18.dp
    ) {
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
                    text = "未授权：开关已禁用",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color(0xFFFF9F0A)
                )
                Text(
                    text = "请先在上方完成 Shizuku 或 Root 授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AppRowCard(
    row: AppRow,
    hasPrivilege: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (allowed: Boolean) -> Unit,
    onClick: () -> Unit
) {
    val info = row.info
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppIcon(info.packageName, size = 46.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    info.appName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    info.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    "UID: ${info.uid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val (dotText, dotColor) = when (row.status) {
                    NetworkStatus.ALLOWED -> "已允许联网" to Color(0xFF34C759)
                    NetworkStatus.BLOCKED -> "已禁止联网" to Color(0xFFFF453A)
                    NetworkStatus.WIFI_ONLY_BLOCKED -> "仅 Wi-Fi 被禁" to Color(0xFFFF9F0A)
                    NetworkStatus.MOBILE_ONLY_BLOCKED -> "仅移动数据被禁" to Color(0xFFFF9F0A)
                    NetworkStatus.UNKNOWN -> "未知（无权限）" to Color.Gray
                    NetworkStatus.ERROR -> "状态检测失败" to Color(0xFFFF453A)
                }
                StatusDot(dotText, dotColor)
                // 开关语义：开 = 允许联网（与需求文档一致）
                LiquidToggle(
                    checked = row.status == NetworkStatus.ALLOWED,
                    onCheckedChange = onToggle,
                    enabled = hasPrivilege &&
                        !row.operating &&
                        row.status != NetworkStatus.UNKNOWN &&
                        row.status != NetworkStatus.ERROR
                )
            }
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
