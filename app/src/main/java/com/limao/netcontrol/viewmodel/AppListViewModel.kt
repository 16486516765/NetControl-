package com.limao.netcontrol.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limao.netcontrol.NetControlApp
import com.limao.netcontrol.data.AppInfo
import com.limao.netcontrol.data.NetworkRule
import com.limao.netcontrol.platform.network.NetworkStatus
import com.limao.netcontrol.platform.network.RuleApplier
import com.limao.netcontrol.platform.network.UidRuleDetail
import com.limao.netcontrol.platform.network.UnsupportedNetworkController
import com.limao.netcontrol.repository.SettingsRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 应用列表行数据：应用信息 + 持久化规则 + 系统实际状态 + 操作态。 */
data class AppRow(
    val info: AppInfo,
    val rule: NetworkRule?,
    /** 系统实际联网状态（读 iptables，非 UI 开关）。 */
    val status: NetworkStatus,
    val error: String? = null,
    val operating: Boolean = false
)

/** 带预计算小写搜索字段的应用，避免每次过滤都 lowercase()。 */
private data class SearchableApp(
    val info: AppInfo,
    val nameLower: String,
    val pkgLower: String
)

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private val netApp = app as NetControlApp
    private val session get() = netApp.session
    private val appRepository get() = netApp.appRepository
    private val ruleRepository get() = netApp.ruleRepository
    private val settings get() = netApp.settingsRepository

    private val _apps = MutableStateFlow<List<SearchableApp>>(emptyList())
    private val _ruleDetails = MutableStateFlow<Map<Int, UidRuleDetail>>(emptyMap())
    private val _isLoading = MutableStateFlow(false)
    private val _loadError = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _operating = MutableStateFlow<Set<String>>(emptySet())
    // per-UID 互斥：同一应用同时只允许一个规则操作，避免快速连点竞态
    private val uidLocks = mutableMapOf<String, Mutex>()
    private fun lockFor(packageName: String): Mutex = synchronized(uidLocks) {
        uidLocks.getOrPut(packageName) { Mutex() }
    }

    val isLoading = _isLoading.asStateFlow()
    val loadError = _loadError.asStateFlow()
    val searchQuery = _searchQuery.asStateFlow()

    private val rules: StateFlow<Map<String, NetworkRule>> =
        ruleRepository.rulesFlow.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyMap()
        )

    @OptIn(FlowPreview::class)
    private val debouncedQuery = _searchQuery
        .debounce(150)
        .distinctUntilChanged()

    /** 列表行：应用 × 规则 × 系统实际状态，支持搜索/排序/过滤。 */
    @OptIn(FlowPreview::class)
    val rows: StateFlow<List<AppRow>> = combine(
        _apps, rules, _ruleDetails, debouncedQuery, _errors, _operating,
        settings.showSystemAppsFlow, settings.showBlockedOnlyFlow, settings.sortModeFlow
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val apps = args[0] as List<SearchableApp>
        val ruleMap = args[1] as Map<String, NetworkRule>
        val details = args[2] as Map<Int, UidRuleDetail>
        val query = (args[3] as String).trim().lowercase()
        val errMap = args[4] as Map<String, String>
        val operatingSet = args[5] as Set<String>
        val showSystem = args[6] as Boolean
        val blockedOnly = args[7] as Boolean
        val sortMode = args[8] as String

        var list = apps.asSequence()
        if (!showSystem) list = list.filter { !it.info.isSystemApp }
        if (query.isNotEmpty()) {
            // 用预计算的小写字段，不再每次 lowercase()
            list = list.filter {
                it.nameLower.contains(query) || it.pkgLower.contains(query)
            }
        }
        val privileged = session.controller !is UnsupportedNetworkController
        var rows = list.map { searchable ->
            val info = searchable.info
            val detail = details[info.uid]
            val status = when {
                !privileged -> NetworkStatus.UNKNOWN
                detail == null -> NetworkStatus.ALLOWED
                detail.unifiedBlocked -> NetworkStatus.BLOCKED
                detail.ifaces.isNotEmpty() -> NetworkStatus.BLOCKED
                else -> NetworkStatus.ALLOWED
            }
            AppRow(
                info = info,
                rule = ruleMap[info.packageName],
                status = status,
                error = errMap[info.packageName],
                operating = operatingSet.contains(info.packageName)
            )
        }
        if (blockedOnly) {
            rows = rows.filter {
                it.status == NetworkStatus.BLOCKED ||
                    it.status == NetworkStatus.WIFI_ONLY_BLOCKED ||
                    it.status == NetworkStatus.MOBILE_ONLY_BLOCKED
            }
        }
        val sorted = when (sortMode) {
            SettingsRepository.SortMode.PACKAGE ->
                rows.sortedBy { it.info.packageName.lowercase() }
            SettingsRepository.SortMode.STATUS ->
                rows.sortedWith(
                    compareByDescending<AppRow> { it.status != NetworkStatus.ALLOWED }
                        .thenBy { it.info.appName.lowercase() }
                )
            else -> rows.sortedBy { it.info.appName.lowercase() }
        }
        sorted.toList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    /** 加载应用 + 一次性拉取全部系统规则状态。 */
    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _loadError.value = null
            try {
                val apps = appRepository.loadApps()
                _apps.value = apps.map {
                    SearchableApp(
                        info = it,
                        nameLower = it.appName.lowercase(),
                        pkgLower = it.packageName.lowercase()
                    )
                }
                refreshStatuses(apps)
            } catch (e: Exception) {
                Log.e(TAG, "load apps failed", e)
                _loadError.value = e.message ?: "加载应用列表失败"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** 刷新系统实际状态（一次 su 调用查全部 UID）。 */
    fun refreshStatuses(apps: List<AppInfo> = _apps.value.map { it.info }) {
        viewModelScope.launch {
            try {
                _ruleDetails.value = session.controller.getAllUidRules()
            } catch (e: Exception) {
                Log.e(TAG, "refresh statuses failed", e)
            }
        }
    }

    /**
     * 切换应用联网开关：真正执行系统规则，成功才持久化；
     * 失败时行内显示错误，绝不显示虚假的“已禁止”。
     */
    fun setAppBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch {
            val info = _apps.value.find { it.info.packageName == packageName }?.info ?: return@launch
            // 同一 UID 串行化：快速连点时以前一次为准，不会两个 Shell 同时改规则
            lockFor(packageName).withLock {
            _operating.value = _operating.value + packageName
            _errors.value = _errors.value - packageName
            try {
                val controller = session.controller
                if (controller is UnsupportedNetworkController) {
                    _errors.value = _errors.value + (packageName to "未获得系统权限，无法控制网络")
                    return@launch
                }
                val current = rules.value[packageName]
                val newRule = (current ?: NetworkRule(packageName, info.uid))
                    .copy(uid = info.uid, blocked = blocked)
                val result = RuleApplier.apply(controller, newRule)
                if (result.isSuccess) {
                    ruleRepository.saveRule(newRule)
                    // 本地更新该 UID 的状态，避免整表重查
                    val cur = _ruleDetails.value.toMutableMap()
                    cur[info.uid] = UidRuleDetail(
                        uid = info.uid,
                        unifiedBlocked = blocked,
                        ifaces = if (blocked) emptySet()
                        else cur[info.uid]?.ifaces.orEmpty()
                    )
                    _ruleDetails.value = cur
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "网络规则应用失败"
                    Log.e(TAG, "setAppBlocked failed for $packageName: $msg")
                    _errors.value = _errors.value + (packageName to msg)
                }
            } finally {
                _operating.value = _operating.value - packageName
            }
            } // lockFor
        }
    }

    fun clearError(packageName: String) {
        _errors.value = _errors.value - packageName
    }

    companion object {
        private const val TAG = "AppListViewModel"
    }
}
