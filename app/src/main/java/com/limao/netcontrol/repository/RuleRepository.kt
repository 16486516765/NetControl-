package com.limao.netcontrol.repository

import android.content.Context
import android.content.SharedPreferences
import com.limao.netcontrol.data.NetworkRule
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 网络规则持久化（SharedPreferences）。只存用户规则，不存 UI 中间态。 */
class RuleRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("network_rules", Context.MODE_PRIVATE)
    }

    private val packagesKey = "rule_packages"

    private fun uidKey(pkg: String) = "rule_uid_$pkg"
    private fun blockedKey(pkg: String) = "rule_blocked_$pkg"
    private fun wifiKey(pkg: String) = "rule_wifi_$pkg"
    private fun mobileKey(pkg: String) = "rule_mobile_$pkg"

    val rulesFlow: Flow<Map<String, NetworkRule>> = callbackFlow {
        fun load(): Map<String, NetworkRule> {
            val pkgs = prefs.getStringSet(packagesKey, emptySet()).orEmpty()
            return pkgs.mapNotNull { pkg ->
                if (!prefs.contains(uidKey(pkg))) return@mapNotNull null
                NetworkRule(
                    packageName = pkg,
                    uid = prefs.getInt(uidKey(pkg), -1),
                    blocked = prefs.getBoolean(blockedKey(pkg), false),
                    wifiBlocked = prefs.getBoolean(wifiKey(pkg), false),
                    mobileBlocked = prefs.getBoolean(mobileKey(pkg), false)
                )
            }.associateBy { it.packageName }
        }

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(load())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun saveRule(rule: NetworkRule) {
        prefs.edit().apply {
            val pkgs = prefs.getStringSet(packagesKey, emptySet()).orEmpty().toMutableSet()
            if (!rule.hasAnyRestriction) {
                // 无任何限制 = 删除规则
                pkgs.remove(rule.packageName)
                remove(uidKey(rule.packageName))
                remove(blockedKey(rule.packageName))
                remove(wifiKey(rule.packageName))
                remove(mobileKey(rule.packageName))
            } else {
                pkgs.add(rule.packageName)
                putInt(uidKey(rule.packageName), rule.uid)
                putBoolean(blockedKey(rule.packageName), rule.blocked)
                putBoolean(wifiKey(rule.packageName), rule.wifiBlocked)
                putBoolean(mobileKey(rule.packageName), rule.mobileBlocked)
            }
            putStringSet(packagesKey, pkgs)
        }.apply()
    }

    suspend fun removeRule(packageName: String) {
        prefs.edit().apply {
            val pkgs = prefs.getStringSet(packagesKey, emptySet()).orEmpty().toMutableSet()
            pkgs.remove(packageName)
            remove(uidKey(packageName))
            remove(blockedKey(packageName))
            remove(wifiKey(packageName))
            remove(mobileKey(packageName))
            putStringSet(packagesKey, pkgs)
        }.apply()
    }

    suspend fun clearAll() {
        prefs.edit().clear().apply()
    }
}
