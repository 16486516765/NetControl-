package com.limao.netcontrol.platform.network

import android.util.Log
import com.limao.netcontrol.data.NetworkRule

/**
 * 把一条 [NetworkRule] 真正应用到系统。
 *
 * 策略：先彻底清理该 UID 的全部规则（统一 + 分组），
 * 再按规则逐项写入。保证系统实际状态与规则完全一致，
 * 不残留历史规则。
 */
object RuleApplier {

    private const val TAG = "RuleApplier"

    suspend fun apply(controller: NetworkController, rule: NetworkRule): Result<Unit> {
        // 1. 清理旧规则，保证幂等
        controller.unblockApp(rule.uid).onFailure {
            Log.e(TAG, "clean failed for ${rule.packageName}", it)
            return Result.failure(it)
        }
        // 2. 统一禁止
        if (rule.blocked) {
            return controller.blockApp(rule.uid).onFailure {
                Log.e(TAG, "block failed for ${rule.packageName}", it)
            }
        }
        // 3. 分组规则（设备不支持时返回 failure，调用方负责提示）
        if (rule.wifiBlocked) {
            controller.setWifiBlocked(rule.uid, true).onFailure {
                Log.e(TAG, "wifi block failed for ${rule.packageName}", it)
                return Result.failure(it)
            }
        }
        if (rule.mobileBlocked) {
            controller.setMobileBlocked(rule.uid, true).onFailure {
                Log.e(TAG, "mobile block failed for ${rule.packageName}", it)
                return Result.failure(it)
            }
        }
        return Result.success(Unit)
    }
}
