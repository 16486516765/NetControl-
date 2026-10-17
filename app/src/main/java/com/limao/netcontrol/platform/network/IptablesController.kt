package com.limao.netcontrol.platform.network

import android.util.Log
import com.limao.netcontrol.data.NetworkRule
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
    /** 探测失败时的详细信息，用于报错时告诉用户真实原因 */
    private var resolveError: String? = null

    private var transportCache: Pair<Long, TransportInfo>? = null

    companion object {
        /** 单 UID 应用脚本的最终验证通过标记（解析 stdout 用）。 */
        private const val APPLY_VERIFY_OK = "__APPLY_VERIFY_OK__"
        private const val APPLY_VERIFY_FAIL = "__APPLY_VERIFY_FAIL__"
        /** 合法接口名：字母数字 + 下划线/点/横线，防止异常值破坏 Shell 脚本解析。 */
        private val IFACE_NAME_REGEX = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
    }

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
            var lastErr = ""
            // 多路径探测：有些设备的 su/sh 环境 PATH 受限，绝对路径兜底
            iptablesBin = listOf(
                "iptables",
                "/system/bin/iptables",
                "/vendor/bin/iptables",
                "/sbin/iptables"
            ).firstOrNull { bin ->
                val r = shell.exec("$bin --version", timeoutMs = 10000)
                if (!r.success) lastErr = "[$bin] exit=${r.exitCode} err=${r.stderr.take(200)}"
                r.success
            }
            if (iptablesBin == null) {
                resolveError = if (lastErr.isNotEmpty()) lastErr else "iptables binary not found in PATH"
                Log.e(logTag, "iptables resolve failed: $resolveError")
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

    /** 给用户看的真实失败原因，而不是笼统的"不可用" */
    protected fun iptablesUnavailableReason(): String {
        return resolveError?.let { "iptables 不可用：$it" }
            ?: "iptables 不可用，无法执行网络控制"
    }

    private suspend fun binsBestEffort(): List<String> {
        if (!resolveBinaries()) return emptyList()
        return listOfNotNull(iptablesBin, ip6tablesBin)
    }

    private suspend fun ruleExists(bin: String, uid: Int, iface: String?): Boolean {
        val r = shell.exec("$bin -C OUTPUT ${ruleSpec(uid, iface)}")
        return r.success
    }

    // ------------------------------------------------------------------
    // NetworkController 实现
    // ------------------------------------------------------------------

    override suspend fun applyRule(rule: NetworkRule): Result<Unit> {
        val transports = detectTransports().getOrNull()
        val needsSplit = rule.wifiBlocked || rule.mobileBlocked
        if (needsSplit && (transports == null || !transports.supportsSplit)) {
            return Result.failure(IllegalStateException("当前控制模式无法可靠区分 Wi-Fi 与移动数据"))
        }
        val desiredIfaces = buildSet {
            if (rule.wifiBlocked) addAll(transports?.wifiInterfaces.orEmpty())
            if (rule.mobileBlocked) addAll(transports?.mobileInterfaces.orEmpty())
        }
        if (!resolveBinaries()) {
            return Result.failure(IllegalStateException(iptablesUnavailableReason()))
        }
        val bin = iptablesBin
            ?: return Result.failure(IllegalStateException(iptablesUnavailableReason()))
        // 差异化 + 单脚本：清理多余、写入缺失、最终验证，一次 Shell 完成
        val script = buildApplyBlock(
            uid = rule.uid,
            desiredUnified = rule.blocked,
            desiredIfaces = desiredIfaces,
            bin = bin,
            bin6 = ip6tablesBin,
            okMarker = APPLY_VERIFY_OK,
            failMarker = APPLY_VERIFY_FAIL,
            failAction = "exit 1"
        )
        val r = shell.exec(script, timeoutMs = 30000)
        if (!r.success || !r.stdout.contains(APPLY_VERIFY_OK)) {
            Log.e(logTag, "apply rule failed (uid=${rule.uid}): ${r.stderr}")
            return Result.failure(IllegalStateException("网络规则应用失败（uid=${rule.uid}）"))
        }
        return Result.success(Unit)
    }

    override suspend fun applyRules(rules: List<NetworkRule>): Map<String, Result<Unit>> {
        if (rules.isEmpty()) return emptyMap()
        // 共享 UID 的规则在批处理里会互相覆盖（后写为准，但先写者的验证已算出），退回逐条处理
        if (rules.map { it.uid }.toSet().size != rules.size) {
            val results = mutableMapOf<String, Result<Unit>>()
            for (rule in rules) results[rule.packageName] = applyRule(rule)
            return results
        }
        if (!resolveBinaries()) {
            val failure = Result.failure<Unit>(IllegalStateException(iptablesUnavailableReason()))
            return rules.associate { it.packageName to failure }
        }
        val bin = iptablesBin
            ?: return rules.associate {
                it.packageName to Result.failure<Unit>(
                    IllegalStateException(iptablesUnavailableReason())
                )
            }
        val transports = detectTransports().getOrNull()
        val results = mutableMapOf<String, Result<Unit>>()
        val script = StringBuilder()
        for (rule in rules) {
            val needsSplit = rule.wifiBlocked || rule.mobileBlocked
            if (needsSplit && (transports == null || !transports.supportsSplit)) {
                results[rule.packageName] = Result.failure(
                    IllegalStateException("当前控制模式无法可靠区分 Wi-Fi 与移动数据")
                )
                continue
            }
            val desiredIfaces = buildSet {
                if (rule.wifiBlocked) addAll(transports?.wifiInterfaces.orEmpty())
                if (rule.mobileBlocked) addAll(transports?.mobileInterfaces.orEmpty())
            }
            script.appendLine(
                buildApplyBlock(
                    uid = rule.uid,
                    desiredUnified = rule.blocked,
                    desiredIfaces = desiredIfaces,
                    bin = bin,
                    bin6 = ip6tablesBin,
                    okMarker = "__UID_${rule.uid}_OK__",
                    failMarker = "__UID_${rule.uid}_FAIL__",
                    failAction = ":"
                )
            )
        }
        if (script.isEmpty()) return results
        val r = shell.exec(script.toString(), timeoutMs = 120000)
        for (rule in rules) {
            if (rule.packageName in results) continue
            val ok = r.success && r.stdout.contains("__UID_${rule.uid}_OK__")
            results[rule.packageName] = if (ok) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IllegalStateException("网络规则应用失败（uid=${rule.uid}）${r.stderr.take(200)}")
                )
            }
        }
        return results
    }

    /**
     * 构建单个 UID 的差异化应用脚本块：清理多余 + 写入缺失 + 最终验证，
     * 全部在一个 Shell 脚本里完成（一次 su/Shizuku 调用）。
     *
     * - 期望存在的规则用 `-C … || -I …`（存在则跳过，不重复写）；
     * - 不期望的统一规则用 `-D` 循环删净；
     * - 不期望的分组规则：枚举全部接口，删除不在期望集合里的分组规则（清陈旧规则）；
     * - 最后验证目标状态：统一规则存在当且仅当期望存在；期望的分组规则都存在；
     *   不期望的接口上无残留分组规则。验证不通过则标记失败。
     * IPv4 严格校验；IPv6 尽力（失败不影响结果，与旧行为一致）。
     */
    private fun buildApplyBlock(
        uid: Int,
        desiredUnified: Boolean,
        desiredIfaces: Set<String>,
        bin: String,
        bin6: String?,
        okMarker: String,
        failMarker: String,
        failAction: String
    ): String = buildString {
        // ---- 1. 统一规则 ----
        if (desiredUnified) {
            appendLine("$bin -w -C OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $bin -w -I OUTPUT -m owner --uid-owner $uid -j REJECT")
            bin6?.let {
                appendLine("$it -w -C OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $it -w -I OUTPUT -m owner --uid-owner $uid -j REJECT || true")
            }
        } else {
            appendLine("for i in 1 2 3 4 5 6 7 8 9 10; do $bin -w -D OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done")
            bin6?.let {
                appendLine("for i in 1 2 3 4 5 6 7 8 9 10; do $it -w -D OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done || true")
            }
        }
        // ---- 2. 期望的分组规则：存在则跳过，不存在则写入 ----
        for (iface in desiredIfaces) {
            appendLine("$bin -w -C OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $bin -w -I OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT")
            bin6?.let {
                appendLine("$it -w -C OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || $it -w -I OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT || true")
            }
        }
        // ---- 3. 陈旧分组规则：枚举全部接口，删除不在期望集合里的分组规则 ----
        appendLine("for iface in \$(ls /sys/class/net 2>/dev/null); do")
        appendLine("  case \"\$iface\" in")
        if (desiredIfaces.isNotEmpty()) {
            appendLine("    ${desiredIfaces.joinToString("|")}) ;;")
        }
        appendLine("    *) for i in 1 2 3; do $bin -w -D OUTPUT -o \$iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done ;;")
        appendLine("  esac")
        appendLine("done")
        bin6?.let {
            appendLine("for iface in \$(ls /sys/class/net 2>/dev/null); do")
            appendLine("  case \"\$iface\" in")
            if (desiredIfaces.isNotEmpty()) {
                appendLine("    ${desiredIfaces.joinToString("|")}) ;;")
            }
            appendLine("    *) for i in 1 2 3; do $it -w -D OUTPUT -o \$iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done || true ;;")
            appendLine("  esac")
            appendLine("done || true")
        }
        // ---- 4. 最终验证（只校验 IPv4，IPv6 尽力不校验） ----
        appendLine("V_OK=1")
        if (desiredUnified) {
            appendLine("$bin -w -C OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || V_OK=0")
        } else {
            appendLine("$bin -w -C OUTPUT -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 && V_OK=0")
        }
        for (iface in desiredIfaces) {
            appendLine("$bin -w -C OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || V_OK=0")
        }
        // 不在期望集合里的接口上不应残留分组规则
        appendLine("for iface in \$(ls /sys/class/net 2>/dev/null); do")
        appendLine("  case \"\$iface\" in")
        if (desiredIfaces.isNotEmpty()) {
            appendLine("    ${desiredIfaces.joinToString("|")}) ;;")
        }
        appendLine("    *) $bin -w -C OUTPUT -o \$iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 && V_OK=0 ;;")
        appendLine("  esac")
        appendLine("done")
        appendLine("if [ \"\$V_OK\" = \"1\" ]; then echo $okMarker; else echo $failMarker; $failAction; fi")
    }

    override suspend fun blockApp(uid: Int): Result<Unit> {
        val bins = binsStrict()
        if (bins.isEmpty()) {
            return Result.failure(IllegalStateException(iptablesUnavailableReason()))
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
            return Result.failure(IllegalStateException(iptablesUnavailableReason() + "，无法解除网络限制"))
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
                // P0-3：IPv6 分组规则也要按接口清理，旧逻辑只删了 IPv6 统一规则，
                // IPv6 分组规则会残留成“幽灵限制”。
                appendLine("for iface in \$(ls /sys/class/net 2>/dev/null); do for i in 1 2 3; do $bin6 -w -D OUTPUT -o \$iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done; done || true")
            }
        }
        val r = shell.exec(script, timeoutMs = 30000)
        if (!r.success) {
            Log.e(logTag, "unblock script failed (uid=$uid): ${r.stderr}")
            return Result.failure(IllegalStateException("删除 iptables 规则失败（uid=$uid）"))
        }
        // P0-3：全量核验——用 IPv4+IPv6 快照确认该 UID 已无任何残留规则，
        // 而不是只检查 IPv4 统一规则。快照读取失败也视为核验失败，绝不谎报“已清理”。
        val snapshot = getAllUidRules()
        if (snapshot == null) {
            Log.e(logTag, "unblock verify failed: snapshot unavailable (uid=$uid)")
            return Result.failure(IllegalStateException("规则清理后无法核验（uid=$uid），请重试"))
        }
        if (snapshot[uid]?.hasAnyRule == true) {
            Log.e(logTag, "rule still exists after unblock (uid=$uid): ${snapshot[uid]}")
            return Result.failure(IllegalStateException("规则清理验证失败（uid=$uid），仍有残留规则"))
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
        // §5-5：接口名拼进 Shell 脚本前做格式校验，异常设备返回值不能破坏脚本解析。
        // Kotlin 侧插值的接口名全部来自这里（detectTransports），在此处统一过滤即可；
        // 脚本里 `ls /sys/class/net` 枚举的部分用 shell 变量展开，不经 Kotlin 插值，天然安全。
        val filtered = names.filter { it != "lo" && it.isNotBlank() && IFACE_NAME_REGEX.matches(it) }
        if (filtered.size != names.count { it != "lo" && it.isNotBlank() }) {
            Log.w(logTag, "dropped invalid interface names: $names")
        }
        val wifi = filtered.filter { n ->
            n.startsWith("wlan") || n.startsWith("wlx") || n == "wifi0"
        }
        val mobile = filtered.filter { n ->
            UidRuleDetail.MOBILE_IFACE_PREFIXES.any { n.startsWith(it) }
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
        // P1-1：逐条核验——脚本整体退出码为 0 不代表每条接口规则都生效
        // （前面某条失败、最后一条成功时退出码仍为 0）。按接口逐个确认目标状态。
        val bin = bins.first()
        val mismatch = ifaces.filter { iface ->
            ruleExists(bin, uid, iface) != blocked
        }
        if (mismatch.isEmpty()) return Result.success(Unit)
        Log.e(logTag, "$label verify failed (uid=$uid): mismatch=$mismatch blocked=$blocked")
        if (blocked) {
            // 回滚：清理本次新加的分组规则，避免“部分生效”残留；
            // 回滚本身尽力而为，结果不影响失败返回。
            val rollback = buildString {
                for (iface in ifaces) {
                    appendLine("for i in 1 2 3; do $bin -w -D OUTPUT -o $iface -m owner --uid-owner $uid -j REJECT >/dev/null 2>&1 || break; done")
                }
            }
            shell.exec(rollback, timeoutMs = 30000)
        }
        return Result.failure(
            IllegalStateException("$label 规则核验失败（uid=$uid），接口 $mismatch 状态与预期不一致")
        )
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
     * 一次 su 调用读取 OUTPUT 链全部 UID 规则，解析出每个 UID 的
     * 统一/分组限制详情。应用列表用它一次性拿到全部状态。
     * IPv4（iptables）必需；IPv6（ip6tables）尽力合并，不能只靠 IPv4 假定 IPv6。
     * 返回 null 表示读取失败（与“已读取且无规则”的空 map 区分）。
     */
    override suspend fun getAllUidRules(): Map<Int, UidRuleDetail>? {
        val bin = binsStrict().firstOrNull() ?: return null
        val r = shell.exec("$bin -w -S OUTPUT", timeoutMs = 30000)
        if (!r.success) {
            Log.e(logTag, "dump rules failed: ${r.stderr}")
            return null
        }
        val result = mutableMapOf<Int, UidRuleDetail>()
        mergeRulesDump(result, r.stdout)
        // IPv6：尽力合并进同一快照
        ip6tablesBin?.let { bin6 ->
            val r6 = shell.exec("$bin6 -w -S OUTPUT", timeoutMs = 30000)
            if (r6.success) {
                mergeRulesDump(result, r6.stdout)
            } else {
                Log.w(logTag, "dump IPv6 rules failed: ${r6.stderr}")
            }
        }
        return result
    }

    /** 把一次 `-S OUTPUT` 的输出解析并合并进快照（IPv4/IPv6 共用）。 */
    private fun mergeRulesDump(result: MutableMap<Int, UidRuleDetail>, dump: String) {
        // 形如：-A OUTPUT -o wlan0 -m owner --uid-owner 10123 -j REJECT
        val uidRegex = Regex("""--uid-owner\s+(\d+)""")
        val ifaceRegex = Regex("""-o\s+(\S+)""")
        for (line in dump.lines()) {
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
    }
}
