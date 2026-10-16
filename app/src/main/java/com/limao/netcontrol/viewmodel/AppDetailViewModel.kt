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
import com.limao.netcontrol.platform.network.UnsupportedNetworkController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用详情：显示实际系统状态；开关修改的是“待应用”草稿，
 * 点击「立即应用」才真正执行系统规则。
 */
class AppDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val netApp = app as NetControlApp
    private val session get() = netApp.session
    private val appRepository get() = netApp.appRepository
    private val ruleRepository get() = netApp.ruleRepository

    private val _info = MutableStateFlow<AppInfo?>(null)
    val info: StateFlow<AppInfo?> = _info.asStateFlow()

    private val _actualStatus = MutableStateFlow(NetworkStatus.UNKNOWN)
    val actualStatus: StateFlow<NetworkStatus> = _actualStatus.asStateFlow()

    private val _actualWifiBlocked = MutableStateFlow(false)
    val actualWifiBlocked: StateFlow<Boolean> = _actualWifiBlocked.asStateFlow()

    private val _actualMobileBlocked = MutableStateFlow(false)
    val actualMobileBlocked: StateFlow<Boolean> = _actualMobileBlocked.asStateFlow()

    // 待应用草稿
    private val _draftBlocked = MutableStateFlow(false)
    val draftBlocked: StateFlow<Boolean> = _draftBlocked.asStateFlow()
    private val _draftWifi = MutableStateFlow(false)
    val draftWifi: StateFlow<Boolean> = _draftWifi.asStateFlow()
    private val _draftMobile = MutableStateFlow(false)
    val draftMobile: StateFlow<Boolean> = _draftMobile.asStateFlow()

    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()

    private val _applyError = MutableStateFlow<String?>(null)
    val applyError: StateFlow<String?> = _applyError.asStateFlow()

    private val _applySuccess = MutableStateFlow(false)
    val applySuccess: StateFlow<Boolean> = _applySuccess.asStateFlow()

    val splitSupported = session.splitSupported

    fun load(packageName: String, cached: AppInfo?) {
        viewModelScope.launch {
            // 缓存缺失时只查单个包，不全量扫描所有应用
            val info = cached ?: appRepository.loadApp(packageName)
            if (info == null) {
                _applyError.value = "应用不存在（可能已卸载）"
                return@launch
            }
            _info.value = info
            refreshActual(info)
        }
    }

    /**
     * 从系统读取实际状态，并用持久化规则初始化草稿。
     * 一次规则快照（IPv4+IPv6）推导状态/Wi-Fi/移动数据三项，不再连续调
     * getStatus()/isWifiBlocked()/isMobileBlocked() 做三次查询。
     */
    fun refreshActual(info: AppInfo? = null) {
        val target = info ?: _info.value ?: return
        viewModelScope.launch {
            val controller = session.controller
            if (controller is UnsupportedNetworkController) {
                _actualStatus.value = NetworkStatus.UNKNOWN
                return@launch
            }
            try {
                val snapshot = controller.getAllUidRules()
                if (snapshot == null) {
                    _actualStatus.value = NetworkStatus.ERROR
                    return@launch
                }
                val detail = snapshot[target.uid]
                _actualStatus.value = when {
                    detail == null -> NetworkStatus.ALLOWED
                    detail.unifiedBlocked -> NetworkStatus.BLOCKED
                    detail.wifiBlockedOnly -> NetworkStatus.WIFI_ONLY_BLOCKED
                    detail.mobileBlockedOnly -> NetworkStatus.MOBILE_ONLY_BLOCKED
                    detail.ifaces.isNotEmpty() -> NetworkStatus.BLOCKED
                    else -> NetworkStatus.ALLOWED
                }
                // 任一 Wi-Fi/移动接口上有分组规则（不含统一规则，与旧 isWifiBlocked/isMobileBlocked 语义一致）
                _actualWifiBlocked.value = detail?.wifiBlocked == true
                _actualMobileBlocked.value = detail?.mobileBlocked == true
            } catch (e: Exception) {
                Log.e(TAG, "refreshActual failed", e)
                _actualStatus.value = NetworkStatus.ERROR
            }
        }
    }

    /** 用持久化规则初始化草稿（进入详情页时调用一次）。 */
    fun setDraftFromRule(rule: NetworkRule?) {
        _draftBlocked.value = rule?.blocked == true
        _draftWifi.value = rule?.wifiBlocked == true
        _draftMobile.value = rule?.mobileBlocked == true
    }

    fun updateDraft(blocked: Boolean, wifi: Boolean, mobile: Boolean) {
        _draftBlocked.value = blocked
        _draftWifi.value = wifi
        _draftMobile.value = mobile
    }

    /** 「立即应用」：真正执行系统规则，成功才持久化。 */
    fun applyNow() {
        val info = _info.value ?: return
        viewModelScope.launch {
            _applying.value = true
            _applyError.value = null
            _applySuccess.value = false
            try {
                val controller = session.controller
                if (controller is UnsupportedNetworkController) {
                    _applyError.value = "未获得系统权限，无法应用规则"
                    return@launch
                }
                val rule = NetworkRule(
                    packageName = info.packageName,
                    uid = info.uid,
                    blocked = _draftBlocked.value,
                    wifiBlocked = _draftWifi.value,
                    mobileBlocked = _draftMobile.value
                )
                val result = RuleApplier.apply(controller, rule)
                if (result.isSuccess) {
                    ruleRepository.saveRule(rule)
                    _applySuccess.value = true
                    refreshActual(info)
                } else {
                    val rawMsg = result.exceptionOrNull()?.message ?: "网络规则应用失败"
                    // 权限丢失才说人话：先做一次新鲜检测，确认没权限才报权限失效
                    val fresh = session.detectPrivilegeNow()
                    val noPrivilege = when (fresh) {
                        is com.limao.netcontrol.platform.network.PrivilegeManager.Privilege.Root -> !fresh.usable
                        is com.limao.netcontrol.platform.network.PrivilegeManager.Privilege.Shizuku -> !fresh.authorized
                        else -> true
                    }
                    _applyError.value = if (noPrivilege) {
                        launch {
                            session.clearPrivilegeCache()
                            session.detect(this)
                        }
                        "权限已失效，请重新授权"
                    } else {
                        rawMsg
                    }
                }
            } finally {
                _applying.value = false
            }
        }
    }

    fun clearApplyState() {
        _applyError.value = null
        _applySuccess.value = false
    }

    companion object {
        private const val TAG = "AppDetailViewModel"
    }
}
