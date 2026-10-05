package com.limao.netcontrol.repository

import android.content.Context
import android.content.SharedPreferences
import com.limao.netcontrol.data.NetworkRule
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject

/**
 * 网络规则持久化（SharedPreferences）。
 * 一、4 优化：整个规则表存成单个 JSON 字符串，一次保存只触发一次监听，
 * 避免之前每个规则写 5 个 key 导致 rows 重算 5 次。
 */
class RuleRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("network_rules", Context.MODE_PRIVATE)
    }

    private val rulesJsonKey = "rules_json_v1"

    private fun load(): Map<String, NetworkRule> {
        val json = prefs.getString(rulesJsonKey, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, NetworkRule>()
            obj.keys().forEach { pkg ->
                val r = obj.getJSONObject(pkg)
                result[pkg] = NetworkRule(
                    packageName = pkg,
                    uid = r.optInt("uid", -1),
                    blocked = r.optBoolean("blocked", false),
                    wifiBlocked = r.optBoolean("wifi", false),
                    mobileBlocked = r.optBoolean("mobile", false)
                )
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAll(map: Map<String, NetworkRule>) {
        val obj = JSONObject()
        for ((pkg, rule) in map) {
            val r = JSONObject()
            r.put("uid", rule.uid)
            r.put("blocked", rule.blocked)
            r.put("wifi", rule.wifiBlocked)
            r.put("mobile", rule.mobileBlocked)
            obj.put(pkg, r)
        }
        prefs.edit().putString(rulesJsonKey, obj.toString()).apply()
    }

    /** 迁移旧格式（多 key）到新格式（单 JSON），只执行一次。 */
    private fun migrateIfNeeded() {
        if (prefs.contains(rulesJsonKey)) return
        val oldPkgs = prefs.getStringSet("rule_packages", emptySet()).orEmpty()
        if (oldPkgs.isEmpty()) {
            // 无旧数据，直接标记已迁移
            prefs.edit().putString(rulesJsonKey, JSONObject().toString()).apply()
            return
        }
        val map = mutableMapOf<String, NetworkRule>()
        for (pkg in oldPkgs) {
            if (!prefs.contains("rule_uid_$pkg")) continue
            map[pkg] = NetworkRule(
                packageName = pkg,
                uid = prefs.getInt("rule_uid_$pkg", -1),
                blocked = prefs.getBoolean("rule_blocked_$pkg", false),
                wifiBlocked = prefs.getBoolean("rule_wifi_$pkg", false),
                mobileBlocked = prefs.getBoolean("rule_mobile_$pkg", false)
            )
        }
        saveAll(map)
        // 清理旧 key
        prefs.edit().apply {
            remove("rule_packages")
            for (pkg in oldPkgs) {
                remove("rule_uid_$pkg")
                remove("rule_blocked_$pkg")
                remove("rule_wifi_$pkg")
                remove("rule_mobile_$pkg")
            }
        }.apply()
    }

    val rulesFlow: Flow<Map<String, NetworkRule>> = callbackFlow {
        migrateIfNeeded()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == rulesJsonKey) trySend(load())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(load())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun saveRule(rule: NetworkRule) {
        val map = load().toMutableMap()
        if (!rule.hasAnyRestriction) {
            map.remove(rule.packageName)
        } else {
            map[rule.packageName] = rule
        }
        saveAll(map)
    }

    suspend fun removeRule(packageName: String) {
        val map = load().toMutableMap()
        map.remove(packageName)
        saveAll(map)
    }

    suspend fun clearAll() {
        prefs.edit().remove(rulesJsonKey).apply()
    }
}
