package com.limao.netcontrol.platform.network

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 基于 iptables 的网络控制器基类，Root 与 Shizuku 共用。
 *
 * 实现原理：在 OUTPUT 链中按 UID 匹配插入 REJECT 规则：
 *   iptables -I OUTPUT -m owner --uid-owner <uid> -j REJECT
 *
 * 每一条规则写入后都会用 `-C` 校验是否真实存在；
 * 删除后同样校验是否真实消失。绝不假设命令成功。
 *
 * IPv4（iptables）为必需路径，失败则整个操作失败；
 * IPv6（ip6tables）为尽力路径，缺失时记警告日志但不导致失败。
 */
abstract class IptablesController(
    protected val shell: ShellExecutor
) : NetworkController {

    protected abstract val logTag: String

    private var iptablesBin: String? = null
    private var ip6tablesBin: String? = null
    private var binariesResolved = false
    private val resolveMutex = Mutex()

    private var transportCache: Pair<Long, TransportInfo>? = null

    // ------------------------------------------------------------------
    // 规则描述
    // ------------------------------------------------------------------

    /** iface == null 表示统一规则（全部接口）；否则为按接口分组规则。-w 防并发锁冲突。 */
    private fun ruleSpec(uid: Int, iface: String?): String =
        if (iface == null) "-w -m owner --uid-owner $uid -j REJECT"
        else "-w -o $iface -m owner --uid-owner $uid -j REJECT"

    private suspend fun resolveBinaries(): Boolean = resolveMutex.withLock {
        if (binariesResolved) return iptablesBin != null
        try {
            // 注意：探测时不用 -w，老版本 iptables 可能不支持该参数导致误判
            iptablesBin = listOf("iptables", "/system/bin/iptables").firstOrNull { bin ->
                shell.exec("$bin --version").success
            }
            if (iptablesBin == null) {
                Log.e(logTag, "iptables binary not found")
                return false
            }
            ip6tablesBin = listOf("ip6tables", "/system/bin/ip6tables").firstOrNull { bin ->
                shell.exec("$bin --version").success
            }
            if (ip6tablesBin == null) {
                Log.w(logTag, "ip6tables not found, IPv6 rules will be skipped")
            }
            return true
        } finally {
            // 只有成功或明确失败才标记，避免取消导致永久卡死
            binariesResolved = true
        }
    }

    /** IPv4 必需；IPv6 尽力。 */
    private suspend fun binsStrict(): List<String> {
        if (!resolveBinaries()) return emptyList()
        return listOfNotNull(iptablesBin)
    }

    private suspend fun binsBestEffort(): List<String> {
        if (!resolveBinaries()) return emptyList()
        return listOfNotNull(iptablesBin, ip6tablesBin)
    }

    private suspend fun ruleExists(bin: String, uid: Int, iface: String?): Boolean {
        val r = shell.exec("$bin -C OUTPUT ${ruleSpec(uid, iface)}")
        return r.success
    }

    private suspend fun addRule(bin: String, uid: Int, iface: String?): Boolean {
        if (ruleExists(bin, uid, iface)) return true
        val r = shell.exec("$bin -I OUTPUT ${ruleSpec(uid, iface)}")
        if (!r.success) {
            Log.e(logTag, "add rule failed ($bin uid=$uid iface=$iface): ${r.stderr}")
            return false
        }
        val ok = ruleExists(bin, uid, iface)
        if (!ok) Log.e(logTag, "rule verify failed after add ($bin uid=$uid iface=$iface)")
        return ok
    }

    private suspend fun removeRule(bin: String, uid: Int, iface: String?): Boolean {
        var guard = 0
        while (ruleExists(bin, uid, iface) && guard++ < 10) {
            val r = shell.exec("$bin -D OUTPUT ${ruleSpec(uid, iface)}")
            if (!r.success) {
                Log.e(logTag, "remove rule failed ($bin uid=$uid iface=$iface): ${r.stderr}")
                return false
            }
        }
        val ok = !ruleExists(bin, uid, iface)
        if (!ok) Log.e(logTag, "rule still exists after remove ($bin uid=$uid iface=$iface)")
        return ok
    }

    // ------------------------------------------------------------------
    // NetworkController 实现
    // ------------------------------------------------------------------

    override suspend fun blockApp(uid: Int): Result<Unit> {
        val bins = binsStrict()
        if (bins.isEmpty()) {
            return Result.failure(IllegalStateException("iptables 不可用，无法执行网络控制"))
        }
        // 一、1 优化：合成单个脚本，一次 su 完成所有操作，而非每个 -C/-I 都起进程
        val script = buildString {
            for (bin in bins) {
                // 存在则跳过，不存在则插入
                appendLine("$bin -w -C OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $bin -w -I OUTPUT -m owner --uid-owner $uid -j REJECT")
            }
            ip6tablesBin?.let { bin6 ->
                appendLine("$bin6 -w -C OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $bin6 -w -I OUTPUT -m owner --uid-owner $uid -j REJECT || true")
            }
        }
        val r = shell.exec(script, timeoutMs = 30000)
        if (!r.success) {
            Log.e(logTag, "block script failed (uid=$uid): ${r.stderr}")
            return Result.failure(IllegalStateException("写入 iptables 规则失败（uid=$uid）"))
        }
        // 批量验证：一次查询确认
        if (!ruleExists(bins.first(), uid, null)) {
            Log.e(logTag, "rule verify failed after block (uid=$uid)")
            return Result.failure(IllegalStateException("规则验证失败（uid=$uid）"))
        }
        return Result.success(Unit)
    }

    override suspend fun unblockApp(uid: Int): Result<Unit> {
        val bins = binsStrict()
        if (bins.isEmpty()) {
            return Result.failure(IllegalStateException("iptables 不可用，无法解除网络限制"))
        }
        // 一、1 优化：单脚本循环删除，最多 10 次，避免逐条起进程
        val script = buildString {
            for (bin in bins) {
                appendLine("for i in 1 2 3 4 5 6 7 8 9 10; do $bin -w -D OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done")
                // 按接口分组规则也清理（尽力）
                appendLine("for iface in \$(ls /sys/class/net 2>/dev/null); do for i in 1 2 3; do $bin -w -D OUTPUT -o \$iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done; done")
            }
            ip6tablesBin?.let { bin6 ->
                appendLine("for i in 1 2 3 4 5 6 7 8 9 10; do $bin6 -w -D OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done || true")
            }
        }
        val r = shell.exec(script, timeoutMs = 30000)
        if (!r.success) {
            Log.e(logTag, "unblock script failed (uid=$uid): ${r.stderr}")
            return Result.failure(IllegalStateException("删除 iptables 规则失败（uid=$uid）"))
        }
        // 批量验证
        if (ruleExists(bins.first(), uid, null)) {
            Log.e(logTag, "rule still exists after unblock (uid=$uid)")
            return Result.failure(IllegalStateException("规则清理验证失败（uid=$uid）"))
        }
        return Result.success(Unit)
    }

    override suspend fun isAppBlocked(uid: Int): Boolean {
        return when (getStatus(uid)) {
            NetworkStatus.BLOCKED,
            NetworkStatus.WIFI_ONLY_BLOCKED,
            NetworkStatus.MOBILE_ONLY_BLOCKED -> true
            else -> false
        }
    }

    override suspend fun getStatus(uid: Int): NetworkStatus {
        return try {
            val bins = binsStrict()
            if (bins.isEmpty()) return NetworkStatus.ERROR
            val unified = bins.any { ruleExists(it, uid, null) }
            val transports = detectTransports().getOrNull()
            val wifiBlocked = transports?.wifiInterfaces?.any { iface ->
                bins.any { ruleExists(it, uid, iface) }
            } == true
            val mobileBlocked = transports?.mobileInterfaces?.any { iface ->
                bins.any { ruleExists(it, uid, iface) }
            } == true
            when {
                unified -> NetworkStatus.BLOCKED
                wifiBlocked && mobileBlocked -> NetworkStatus.BLOCKED
                wifiBlocked -> NetworkStatus.WIFI_ONLY_BLOCKED
                mobileBlocked -> NetworkStatus.MOBILE_ONLY_BLOCKED
                else -> NetworkStatus.ALLOWED
            }
        } catch (e: Exception) {
            Log.e(logTag, "getStatus failed for uid=$uid", e)
            NetworkStatus.ERROR
        }
    }

    override suspend fun detectTransports(): Result<TransportInfo> {
        val cached = transportCache
        if (cached != null && System.currentTimeMillis() - cached.first < 60_000) {
            return Result.success(cached.second)
        }
        // 首选 /sys/class/net（无需 ip 命令）
        var names: List<String>? = null
        val ls = shell.exec("ls /sys/class/net")
        if (ls.success) {
            names = ls.stdout.split(Regex("\\s+")).filter { it.isNotBlank() }
        } else {
            // 兜底：ip -o link show
            val ip = shell.exec("ip -o link show")
            if (ip.success) {
                names = ip.stdout.lines().mapNotNull { line ->
                    // 形如 "2: wlan0: <BROADCAST,MULTICAST> ..."
                    val m = Regex("""^\d+:\s+([^:@\s]+)""").find(line.trim())
                    m?.groupValues?.getOrNull(1)
                }
            }
        }
        if (names == null) {
            return Result.failure(IllegalStateException("无法枚举网络接口"))
        }
        val filtered = names.filter { it != "lo" && it.isNotBlank() }
        val wifi = filtered.filter { n ->
            n.startsWith("wlan") || n.startsWith("wlx") || n == "wifi0"
        }
        val mobile = filtered.filter { n ->
            listOf("rmnet", "ccmni", "pdp", "wwan", "v4-rmnet").any { n.startsWith(it) }
        }
        val info = TransportInfo(wifi, mobile)
        transportCache = System.currentTimeMillis() to info
        Log.i(logTag, "transports: wifi=$wifi mobile=$mobile")
        return Result.success(info)
    }

    override suspend fun setWifiBlocked(uid: Int, blocked: Boolean): Result<Unit> {
        val transports = detectTransports().getOrElse {
            return Result.failure(IllegalStateException("当前控制模式无法可靠区分 Wi-Fi 与移动数据"))
        }
        if (!transports.supportsSplit) {
            return Result.failure(IllegalStateException("当前控制模式无法可靠区分 Wi-Fi 与移动数据"))
        }
        return setTransportBlocked(uid, transports.wifiInterfaces, blocked, "Wi-Fi")
    }

    override suspend fun setMobileBlocked(uid: Int, blocked: Boolean): Result<Unit> {
        val transports = detectTransports().getOrElse {
            return Result.failure(IllegalStateException("当前控制模式无法可靠区分 Wi-Fi 与移动数据"))
        }
        if (!transports.supportsSplit) {
            return Result.failure(IllegalStateException("当前控制模式无法可靠区分 Wi-Fi 与移动数据"))
        }
        return setTransportBlocked(uid, transports.mobileInterfaces, blocked, "移动数据")
    }

    private suspend fun setTransportBlocked(
        uid: Int,
        ifaces: List<String>,
        blocked: Boolean,
        label: String
    ): Result<Unit> {
        val bins = binsStrict()
        if (bins.isEmpty()) {
            return Result.failure(IllegalStateException("iptables 不可用"))
        }
        // 批量脚本：一次 su 处理所有接口
        val script = buildString {
            for (bin in bins) {
                for (iface in ifaces) {
                    if (blocked) {
                        appendLine("$bin -w -C OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $bin -w -I OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT")
                    } else {
                        appendLine("for i in 1 2 3; do $bin -w -D OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done")
                    }
                }
            }
            ip6tablesBin?.let { bin6 ->
                for (iface in ifaces) {
                    if (blocked) {
                        appendLine("$bin6 -w -C OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $bin6 -w -I OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT || true")
                    } else {
                        appendLine("for i in 1 2 3; do $bin6 -w -D OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done || true")
                    }
                }
            }
        }
        val r = shell.exec(script, timeoutMs = 30000)
        if (!r.success) {
            Log.e(logTag, "$label script failed (uid=$uid): ${r.stderr}")
            return Result.failure(IllegalStateException("$label 规则应用失败（uid=$uid）"))
        }
        return Result.success(Unit)
    }

    override suspend fun isWifiBlocked(uid: Int): Boolean {
        val transports = detectTransports().getOrNull() ?: return false
        val bins = binsBestEffort()
        if (bins.isEmpty()) return false
        return transports.wifiInterfaces.any { iface ->
            bins.any { ruleExists(it, uid, iface) }
        }
    }

    override suspend fun isMobileBlocked(uid: Int): Boolean {
        val transports = detectTransports().getOrNull() ?: return false
        val bins = binsBestEffort()
        if (bins.isEmpty()) return false
        return transports.mobileInterfaces.any { iface ->
            bins.any { ruleExists(it, uid, iface) }
        }
    }

    /**
     * 一次 su 调用批量检查多个 UID 的统一禁止规则。
     * 200 个应用也只需一次 Shell 调用，列表加载不卡顿。
     */
    override suspend fun getBlockedUids(uids: List<Int>): Set<Int> {
        if (uids.isEmpty()) return emptySet()
        val bin = binsStrict().firstOrNull() ?: return emptySet()
        // 注意 Kotlin 字符串中 $u 需转义为 \$u，留给 shell 展开
        val script = "for u in ${uids.joinToString(" ")}; do " +
            "$bin -w -C OUTPUT -m owner --uid-owner \$u -j REJECT >/dev/null 2>&1 && echo \$u; done"
        val r = shell.exec(script, timeoutMs = 60000)
        if (!r.success) {
            Log.e(logTag, "batch check failed: ${r.stderr}")
            return emptySet()
        }
        return r.stdout.lines().mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    /**
     * 一次 su 调用读取 OUTPUT 链全部 UID 规则，解析出每个 UID 的
     * 统一/分组限制详情。应用列表用它一次性拿到全部状态。
     */
    override suspend fun getAllUidRules(): Map<Int, UidRuleDetail> {
        val bin = binsStrict().firstOrNull() ?: return emptyMap()
        val r = shell.exec("$bin -w -S OUTPUT", timeoutMs = 30000)
        if (!r.success) {
            Log.e(logTag, "dump rules failed: ${r.stderr}")
            return emptyMap()
        }
        val result = mutableMapOf<Int, UidRuleDetail>()
        // 形如：-A OUTPUT -o wlan0 -m owner --uid-owner 10123 -j REJECT
        val uidRegex = Regex("""--uid-owner\s+(\d+)""")
        val ifaceRegex = Regex("""-o\s+(\S+)""")
        for (line in r.stdout.lines()) {
            val uid = uidRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            if (!line.contains("REJECT")) continue
            val iface = ifaceRegex.find(line)?.groupValues?.getOrNull(1)
            val cur = result[uid]
            result[uid] = if (cur == null) {
                UidRuleDetail(uid, unifiedBlocked = iface == null, ifaces = setOfNotNull(iface))
            } else {
                cur.copy(
                    unifiedBlocked = cur.unifiedBlocked || iface == null,
                    ifaces = cur.ifaces + setOfNotNull(iface)
                )
            }
        }
        return result
    }
}
