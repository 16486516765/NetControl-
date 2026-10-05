package com.limao.netcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.platform.network.RuleApplier
import com.limao.netcontrol.platform.network.UnsupportedNetworkController
import com.limao.netcontrol.repository.AppRepository
import com.limao.netcontrol.repository.RuleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用安装 / 卸载 / 更新：
 * - 卸载：自动清理对应规则，不留无效规则。
 * - 更新：UID 可能变化，重新检测并重新应用规则。
 */
class PackageChangeReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg == context.packageName) return
        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        val appContext = context.applicationContext
        val pending = goAsync()

        when (intent.action) {
            Intent.ACTION_PACKAGE_REMOVED -> {
                if (replacing) {
                    pending.finish()
                    return // 更新流程中的移除，稍后由 REPLACED 处理
                }
                scope.launch {
                    try {
                        val repo = RuleRepository(appContext)
                        val rule = repo.rulesFlow.first()[pkg]
                        repo.removeRule(pkg)
                        // 二、6 修复：同时清理系统 iptables 规则，避免 UID 被新应用复用后继承封禁
                        if (rule != null) {
                            try {
                                val session = (appContext as com.limao.netcontrol.NetControlApp).session
                                session.controller.unblockApp(rule.uid)
                                Log.i(TAG, "system rule cleaned for uninstalled $pkg (uid=${rule.uid})")
                            } catch (e: Exception) {
                                Log.e(TAG, "clean system rule failed for $pkg", e)
                            }
                        }
                        Log.i(TAG, "rule cleaned for uninstalled $pkg")
                    } catch (e: Exception) {
                        Log.e(TAG, "clean rule failed for $pkg", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                scope.launch {
                    try {
                        val repo = RuleRepository(appContext)
                        val rule = repo.rulesFlow.first()[pkg]
                        if (rule != null) {
                            val newUid = AppRepository(appContext).getUid(pkg)
                            if (newUid != null) {
                                if (newUid == rule.uid) {
                                    Log.i(TAG, "re-apply rule for updated $pkg (uid unchanged)")
                                } else {
                                    Log.i(TAG, "uid changed for $pkg: ${rule.uid} -> $newUid")
                                }
                                val pm = PrivilegeManager(appContext)
                                val controller =
                                    pm.createController(pm.detectPrivilege())
                                if (controller !is UnsupportedNetworkController) {
                                    val updated = rule.copy(uid = newUid)
                                    if (RuleApplier.apply(controller, updated).isSuccess) {
                                        repo.saveRule(updated)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "re-apply rule failed for $pkg", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            else -> pending.finish()
        }
    }

    companion object {
        private const val TAG = "PackageChangeReceiver"
    }
}
