package com.limao.netcontrol

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.limao.netcontrol.data.NetworkRule
import com.limao.netcontrol.platform.network.NetworkController
import com.limao.netcontrol.platform.network.NetworkStatus
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.platform.network.RuleApplier
import com.limao.netcontrol.platform.network.UnsupportedNetworkController
import com.limao.netcontrol.repository.AppRepository
import com.limao.netcontrol.repository.RuleRepository
import com.limao.netcontrol.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 全局会话：持有特权检测结果、当前网络控制器、规则校验。
 * 被多个 ViewModel 共享，避免重复检测 Root / Shizuku。
 */
class NetControlSession(
    private val privilegeManager: PrivilegeManager,
    private val appRepository: AppRepository,
    private val ruleRepository: RuleRepository,
    private val settingsRepository: SettingsRepository
) {

    private val _privilege =
        MutableStateFlow<PrivilegeManager.Privilege>(PrivilegeManager.Privilege.Detecting)
    val privilege: StateFlow<PrivilegeManager.Privilege> = _privilege.asStateFlow()

    /** 当前生效的网络控制器。UI 层禁止直接 new 控制器，必须经由这里。 */
    var controller: NetworkController = UnsupportedNetworkController()
        private set

    private val _splitSupported = MutableStateFlow<Boolean?>(null)
    val splitSupported: StateFlow<Boolean?> = _splitSupported.asStateFlow()

    private val _startupVerified = MutableStateFlow(false)
    val startupVerified: StateFlow<Boolean> = _startupVerified.asStateFlow()

    private val _driftFixed = MutableStateFlow(0)
    val driftFixed: StateFlow<Int> = _driftFixed.asStateFlow()

    /** 按 Root > Shizuku > 无特权检测，并做启动规则校验。 */
    fun detect(viewModelScope: kotlinx.coroutines.CoroutineScope) {
        viewModelScope.launch {
            _privilege.value = PrivilegeManager.Privilege.Detecting
            _startupVerified.value = false
            try {
                val p = privilegeManager.detectPrivilege()
                _privilege.value = p
                controller = privilegeManager.createController(p)
                Log.i(TAG, "privilege=$p controller=${controller.controllerName}")
                _splitSupported.value =
                    if (controller is UnsupportedNetworkController) false
                    else controller.detectTransports().getOrNull()?.supportsSplit == true
                verifyRulesOnStartup()
            } catch (e: Exception) {
                Log.e(TAG, "detect failed", e)
                _privilege.value = PrivilegeManager.Privilege.None
                controller = UnsupportedNetworkController()
            } finally {
                _startupVerified.value = true
            }
        }
    }

    fun clearPrivilegeCache() = privilegeManager.clearCache()

    /**
     * 启动校验：读取持久化规则 → 检查系统实际规则 → 对比 →
     * UID 变化则更新、系统规则丢失则重新应用 → 更新 UI 状态。
     * 绝不显示与系统实际不符的状态。
     */
    private suspend fun verifyRulesOnStartup() {
        if (controller is UnsupportedNetworkController) {
            Log.i(TAG, "skip verify: no privilege")
            return
        }
        val rules = ruleRepository.rulesFlow.first()
        if (rules.isEmpty()) return
        var fixed = 0
        val blockedUids = (controller as? com.limao.netcontrol.platform.network.IptablesController)
            ?.getBlockedUids(rules.values.map { it.uid }.distinct())
            .orEmpty()
        for ((pkg, rule) in rules) {
            // 1. 应用已卸载 → 清理规则
            val currentUid = appRepository.getUid(pkg)
            if (currentUid == null) {
                ruleRepository.removeRule(pkg)
                fixed++
                continue
            }
            // 2. UID 变化（应用更新）→ 更新规则并重新应用
            var effectiveRule = rule
            if (currentUid != rule.uid) {
                effectiveRule = rule.copy(uid = currentUid)
                ruleRepository.saveRule(effectiveRule)
                Log.i(TAG, "uid changed $pkg: ${rule.uid} -> $currentUid")
            }
            // 3. 系统实际规则与持久化规则对比
            val expectedBlocked = effectiveRule.hasAnyRestriction
            val actualBlocked = blockedUids.contains(effectiveRule.uid) ||
                controller.isAppBlocked(effectiveRule.uid)
            if (expectedBlocked && !actualBlocked) {
                // 系统规则丢失 → 重新应用
                Log.w(TAG, "rule lost for $pkg, re-applying")
                if (RuleApplier.apply(controller, effectiveRule).isSuccess) fixed++
            } else if (!expectedBlocked && actualBlocked) {
                // 系统有多余规则 → 清理，保证一致
                Log.w(TAG, "stale system rule for $pkg, cleaning")
                if (controller.unblockApp(effectiveRule.uid).isSuccess) fixed++
            }
        }
        _driftFixed.value = fixed
        if (fixed > 0) Log.i(TAG, "startup verify fixed $fixed rules")
    }

    /** 开机后待恢复：用户点击后手动恢复全部规则。 */
    suspend fun restorePendingRules(): Result<Pair<Int, Int>> {
        val rules = ruleRepository.rulesFlow.first()
        var ok = 0
        var failed = 0
        for ((_, rule) in rules) {
            if (RuleApplier.apply(controller, rule).isSuccess) ok++ else failed++
        }
        if (failed == 0) settingsRepository.setPendingRestore(false)
        return if (failed == 0) Result.success(ok to failed)
        else Result.failure(IllegalStateException("部分规则恢复失败：成功 $ok，失败 $failed"))
    }

    companion object {
        private const val TAG = "NetControlSession"
    }
}
