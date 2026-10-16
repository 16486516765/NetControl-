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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
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

/** 带预计算小写搜索字段的应用，避免每次过滤/排序都 lowercase()。 */
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
    /** null=尚未成功读取系统规则（显示未知），emptyMap=已读取且无限制规则 */
    private val _ruleDetails = MutableStateFlow<Map<Int, UidRuleDetail>?>(null)
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

    /** 列表行基础数据：应用 × 规则 × 系统实际状态（不含操作中/错误等局部 UI 态）。 */
    @OptIn(FlowPreview::class)
    private val baseRows: StateFlow<List<AppRow>> = combine(
        _apps, rules, _ruleDetails, debouncedQuery,
        settings.showSystemAppsFlow, settings.showBlockedOnlyFlow, settings.sortModeFlow
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val apps = args[0] as List<SearchableApp>
        val ruleMap = args[1] as Map<String, NetworkRule>
        val details = args[2] as Map<Int, UidRuleDetail>?
        val query = (args[3] as String).trim().lowercase()
        val showSystem = args[4] as Boolean
        val blockedOnly = args[5] as Boolean
        val sortMode = args[6] as String

        var list = apps.asSequence()
        if (!showSystem) list = list.filter { !it.info.isSystemApp }
        if (query.isNotEmpty()) {
            // 用预计算的小写字段，不再每次 lowercase()
            list = list.filter {
                it.nameLower.contains(query) || it.pkgLower.contains(query)
            }
        }
        val privileged = session.controller !is UnsupportedNetworkController
        // 状态算一次：先为每项算出 status，再一次排序（不在比较器里反复算）。
        // STATUS 排序用完整状态：统一封禁/分组限制都算“受限”排前面，ALLOWED 排后面
        // （旧逻辑只看 unifiedBlocked，会把分组限制误当成 ALLOWED 排序）。
        val withStatus = list.map { searchable ->
            val info = searchable.info
            val detail = details?.get(info.uid)
            val status = when {
                !privileged -> NetworkStatus.UNKNOWN
                details == null -> NetworkStatus.UNKNOWN
                detail == null -> NetworkStatus.ALLOWED
                detail.unifiedBlocked -> NetworkStatus.BLOCKED
                // 二、7 修复：与详情页一致，区分单向限制
                detail.wifiBlockedOnly -> NetworkStatus.WIFI_ONLY_BLOCKED
                detail.mobileBlockedOnly -> NetworkStatus.MOBILE_ONLY_BLOCKED
                detail.ifaces.isNotEmpty() -> NetworkStatus.BLOCKED
                else -> NetworkStatus.ALLOWED
            }
            searchable to status
        }
        val sorted = when (sortMode) {
            SettingsRepository.SortMode.PACKAGE ->
                withStatus.sortedBy { it.first.pkgLower }
            SettingsRepository.SortMode.STATUS ->
                // 一次排序：受限在前（按名称）、允许在后（按名称），稳定且只排一次
                withStatus.sortedWith(
                    compareByDescending<Pair<SearchableApp, NetworkStatus>> {
                        it.second != NetworkStatus.ALLOWED
                    }.thenBy { it.first.nameLower }
                )
            else -> withStatus.sortedBy { it.first.nameLower }
        }
        var rows = sorted.asSequence().map { (searchable, status) ->
            AppRow(
                info = searchable.info,
                rule = ruleMap[searchable.info.packageName],
                status = status,
                error = null,
                operating = false
            )
        }
        if (blockedOnly) {
            rows = rows.filter {
                it.status == NetworkStatus.BLOCKED ||
                    it.status == NetworkStatus.WIFI_ONLY_BLOCKED ||
                    it.status == NetworkStatus.MOBILE_ONLY_BLOCKED
            }
        }
        rows.toList()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 列表行：基础列表 + 局部 UI 态（某行的操作中/错误）。
     * 操作态变化时只做一次 copy 附加，不再重新过滤/排序整张列表。
     */
    val rows: StateFlow<List<AppRow>> = combine(
        baseRows, _errors, _operating
    ) { base, errMap, operatingSet ->
        if (errMap.isEmpty() && operatingSet.isEmpty()) {
            base
        } else {
            base.map {
                it.copy(
                    error = errMap[it.info.packageName],
                    operating = operatingSet.contains(it.info.packageName)
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
    }

    private var loaded = false

    /**
     * 加载应用 + 一次性拉取全部系统规则状态。
     * 一、5 优化：已加载过则跳过扫描（包变更由 PackageChangeReceiver 触发增量更新），
     * 避免每次回到首页都重扫 500 个应用。
     */
    fun load(force: Boolean = false) {
        if (loaded && !force) return
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
                refreshStatuses()
            } catch (e: Exception) {
                Log.e(TAG, "load apps failed", e)
                _loadError.value = e.message ?: "加载应用列表失败"
            } finally {
                _isLoading.value = false
            }
            loaded = true
        }
    }

    /** 包变更后强制刷新（应用列表缓存与 AppRepository 缓存一起失效）。 */
    fun invalidateCache() {
        loaded = false
        appRepository.invalidateAppsCache()
    }

    /** 刷新系统实际状态（一次 su 调用查全部 UID 的 IPv4+IPv6 快照）。失败时设为 null，UI 显示未知而非虚假的允许。 */
    fun refreshStatuses() {
        viewModelScope.launch {
            try {
                // getAllUidRules 返回 null 表示读取失败（与“已读取且无规则”的空 map 区分）
                _ruleDetails.value = session.controller.getAllUidRules()
            } catch (e: Exception) {
                Log.e(TAG, "refresh statuses failed", e)
                _ruleDetails.value = null
            }
        }
    }

    init {
        // 二、1 修复：特权检测完成后刷新一次，避免启动竞态导致状态永远错误
        viewModelScope.launch {
            session.startupVerified.collect { verified ->
                if (verified && _apps.value.isNotEmpty()) {
                    refreshStatuses()
                }
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
                    val cur = (_ruleDetails.value ?: emptyMap()).toMutableMap()
                    cur[info.uid] = UidRuleDetail(
                        uid = info.uid,
                        unifiedBlocked = blocked,
                        ifaces = if (blocked) emptySet()
                        else cur[info.uid]?.ifaces.orEmpty()
                    )
                    _ruleDetails.value = cur
                } else {
                    val rawMsg = result.exceptionOrNull()?.message ?: "网络规则应用失败"
                    Log.e(TAG, "setAppBlocked failed for $packageName: $rawMsg")
                    // 权限丢失才说人话：先做一次新鲜检测，确认没权限才报权限失效
                    val fresh = session.detectPrivilegeNow()
                    val noPrivilege = when (fresh) {
                        is com.limao.netcontrol.platform.network.PrivilegeManager.Privilege.Root -> !fresh.usable
                        is com.limao.netcontrol.platform.network.PrivilegeManager.Privilege.Shizuku -> !fresh.authorized
                        else -> true
                    }
                    val msg = if (noPrivilege) {
                        // 触发权限重检，UI 自动更新为禁用态
                        launch {
                            session.clearPrivilegeCache()
                            session.detect(this)
                        }
                        "权限已失效，请重新授权"
                    } else {
                        rawMsg
                    }
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
