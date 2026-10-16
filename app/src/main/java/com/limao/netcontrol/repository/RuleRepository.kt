package com.limao.netcontrol.repository

import android.content.Context
import android.content.SharedPreferences
import com.limao.netcontrol.data.NetworkRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 网络规则持久化（SharedPreferences）。
 * 一、4 优化：整个规则表存成单个 JSON 字符串，一次保存只触发一次监听，
 * 避免之前每个规则写 5 个 key 导致 rows 重算 5 次。
 *
 * 并发与线程模型：
 * - 单份内存规则表 + Mutex：所有读-改-写都在锁内完成，并发保存不会互相覆盖丢失；
 * - JSON 编解码放 Dispatchers.Default/IO，不在主线程做；
 * - 持久化与内存更新在同一锁内按固定顺序执行（先持久化，再更新内存），
 *   外部变更经 prefs 监听回流时同样在锁内重读，保证内存与磁盘一致；
 * - prefs 监听只在 rulesFlow 被收集时注册（callbackFlow 生命周期），不常驻泄漏。
 */
class RuleRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("network_rules", Context.MODE_PRIVATE)
    }

    private val rulesJsonKey = "rules_json_v1"

    /** 保护内存规则表与持久化顺序的互斥锁。 */
    private val mutex = Mutex()

    /** 内存中的唯一规则表；memoryJson 是生成它的 prefs JSON 字符串（用于判断是否过期）。 */
    private var memoryRules: Map<String, NetworkRule>? = null
    private var memoryJson: String? = null

    private fun parseJson(json: String?): Map<String, NetworkRule> {
        if (json == null) return emptyMap()
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

    private fun encodeJson(map: Map<String, NetworkRule>): String {
        val obj = JSONObject()
        for ((pkg, rule) in map) {
            val r = JSONObject()
            r.put("uid", rule.uid)
            r.put("blocked", rule.blocked)
            r.put("wifi", rule.wifiBlocked)
            r.put("mobile", rule.mobileBlocked)
            obj.put(pkg, r)
        }
        return obj.toString()
    }

    /** 必须在 mutex 内调用：从 prefs 重读并更新内存表（JSON 解析放 IO）。 */
    private suspend fun reloadFromPrefs(): Map<String, NetworkRule> {
        val json = prefs.getString(rulesJsonKey, null)
        val parsed = withContext(Dispatchers.IO) { parseJson(json) }
        memoryRules = parsed
        memoryJson = json
        return parsed
    }

    /** 必须在 mutex 内调用：内存表过期（磁盘 JSON 已变）时重读，否则直接复用。 */
    private suspend fun ensureFresh() {
        if (memoryRules == null || prefs.getString(rulesJsonKey, null) != memoryJson) {
            reloadFromPrefs()
        }
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
        val json = encodeJson(map)
        prefs.edit().putString(rulesJsonKey, json).apply()
        memoryRules = map
        memoryJson = json
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
        val initial = withContext(Dispatchers.IO) {
            mutex.withLock {
                migrateIfNeeded()
                ensureFresh()
                memoryRules!!
            }
        }
        trySend(initial)
        // 外部变更（另一 RuleRepository 实例 / 另一进程改了 prefs）：
        // 在锁内重读当前磁盘 JSON 并推送；每次重读都读最新值，不会把旧快照推回去。
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == rulesJsonKey) {
                launch {
                    val fresh = mutex.withLock { reloadFromPrefs() }
                    trySend(fresh)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    suspend fun saveRule(rule: NetworkRule) = mutex.withLock {
        migrateIfNeeded()
        ensureFresh()
        val map = memoryRules!!.toMutableMap()
        if (!rule.hasAnyRestriction) {
            map.remove(rule.packageName)
        } else {
            map[rule.packageName] = rule
        }
        // 先持久化（JSON 编码放 Default），再更新内存，顺序固定，避免半更新状态
        val json = withContext(Dispatchers.Default) { encodeJson(map) }
        prefs.edit().putString(rulesJsonKey, json).apply()
        memoryRules = map
        memoryJson = json
    }

    suspend fun removeRule(packageName: String) = mutex.withLock {
        migrateIfNeeded()
        ensureFresh()
        val map = memoryRules!!.toMutableMap()
        map.remove(packageName)
        val json = withContext(Dispatchers.Default) { encodeJson(map) }
        prefs.edit().putString(rulesJsonKey, json).apply()
        memoryRules = map
        memoryJson = json
    }

    suspend fun clearAll() = mutex.withLock {
        prefs.edit().remove(rulesJsonKey).apply()
        memoryRules = emptyMap()
        memoryJson = null
    }
}
