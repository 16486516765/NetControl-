package com.limao.netcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.platform.network.RuleApplier
import com.limao.netcontrol.platform.network.UnsupportedNetworkController
import com.limao.netcontrol.repository.RuleRepository
import com.limao.netcontrol.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 开机自动恢复网络规则。
 *
 * 注意：部分设备后台限制严格，或 Shizuku 开机时尚未运行，
 * 此时无法可靠恢复。本 Receiver 会标记 pendingRestore，
 * App 下次启动时提示用户手动恢复，并在设置页明确说明。
 * 绝不伪造“恢复成功”。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    val settings = SettingsRepository(context.applicationContext)
                    if (!settings.isBootRestoreEnabled()) {
                        Log.i(TAG, "boot restore disabled, skip")
                        return@runBlocking
                    }
                    val privilegeManager =
                        PrivilegeManager(context.applicationContext)
                    val privilege = privilegeManager.detectPrivilege()
                    val controller = privilegeManager.createController(privilege)
                    if (controller is UnsupportedNetworkController) {
                        // 无特权（常见：Shizuku 开机未运行）：标记待恢复，不伪造成功
                        Log.w(TAG, "no privilege at boot ($privilege), mark pending restore")
                        settings.setPendingRestore(true)
                        return@runBlocking
                    }
                    val rules = RuleRepository(context.applicationContext).rulesFlow.first()
                    var ok = 0
                    var failed = 0
                    for ((_, rule) in rules) {
                        if (RuleApplier.apply(controller, rule).isSuccess) ok++ else failed++
                    }
                    Log.i(TAG, "boot restore done: ok=$ok failed=$failed total=${rules.size}")
                    if (failed > 0) settings.setPendingRestore(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "boot restore failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
