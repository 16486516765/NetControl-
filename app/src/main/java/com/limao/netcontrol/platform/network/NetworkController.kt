package com.limao.netcontrol.platform.network

import com.limao.netcontrol.data.NetworkRule

/**
 * 应用联网状态。只反映通过系统规则实际检测到的状态，
 * 绝不根据 UI 开关伪造。
 */
enum class NetworkStatus {
    /** 无任何网络限制规则 */
    ALLOWED,

    /** 被统一禁止联网（或混合规则导致完全无网） */
    BLOCKED,

    /** 仅 Wi-Fi 被禁止 */
    WIFI_ONLY_BLOCKED,

    /** 仅移动数据被禁止 */
    MOBILE_ONLY_BLOCKED,

    /** 无法检测（例如缺少执行查询的权限） */
    UNKNOWN,

    /** 检测过程出错 */
    ERROR
}

/** 设备上的传输接口信息，用于判断是否可可靠区分 Wi-Fi / 移动数据。 */
data class TransportInfo(
    val wifiInterfaces: List<String>,
    val mobileInterfaces: List<String>
) {
    val supportsSplit: Boolean
        get() = wifiInterfaces.isNotEmpty() && mobileInterfaces.isNotEmpty()
}

/**
 * 网络控制器抽象。UI 层只允许通过该接口操作网络规则，
 * 禁止在 Activity / Compose 中直接执行 Shell 命令。
 */
interface NetworkController {

    /** 控制器名称：Root / Shizuku / Unsupported */
    val controllerName: String

    /** 禁止指定 UID 的全部联网（真正写入系统规则并校验）。 */
    suspend fun blockApp(uid: Int): Result<Unit>

    /** 解除指定 UID 的全部联网限制（含 Wi-Fi / 移动数据分组规则）。 */
    suspend fun unblockApp(uid: Int): Result<Unit>

    /** 指定 UID 当前是否被禁止联网（任一分组规则存在即视为被禁）。 */
    suspend fun isAppBlocked(uid: Int): Boolean

    /** 查询指定 UID 的实际联网状态（读系统规则，而非读 UI）。 */
    suspend fun getStatus(uid: Int): NetworkStatus

    /** 探测设备是否可可靠区分 Wi-Fi 与移动数据接口。 */
    suspend fun detectTransports(): Result<TransportInfo>

    /** 设置仅 Wi-Fi 禁止。设备不支持分组控制时返回 failure，绝不伪造。 */
    suspend fun setWifiBlocked(uid: Int, blocked: Boolean): Result<Unit>

    /** 设置仅移动数据禁止。设备不支持分组控制时返回 failure，绝不伪造。 */
    suspend fun setMobileBlocked(uid: Int, blocked: Boolean): Result<Unit>

    suspend fun isWifiBlocked(uid: Int): Boolean

    suspend fun isMobileBlocked(uid: Int): Boolean

    /**
     * 批量查询一批 UID 是否被（统一规则）禁止联网。
     * 用于应用列表一次性加载，避免逐个执行 su。
     * 默认实现返回空集；IptablesController 提供真正的批量实现。
     */
    suspend fun getBlockedUids(uids: List<Int>): Set<Int> = emptySet()

    /**
     * 一次性读取全部 UID 的规则详情（统一/分组，IPv4 + IPv6 合并）。
     * 返回 null 表示读取失败（与“已读取且无规则”的空 map 区分）；
     * 默认返回 null；IptablesController 提供真正实现。
     */
    suspend fun getAllUidRules(): Map<Int, UidRuleDetail>? = null

    /**
     * 把一条 [NetworkRule] 应用到系统（差异化：只删多余、只加缺失，最后验证）。
     * 默认实现是旧序列（先清理再重写），供不支持差异化的控制器使用；
     * IptablesController 覆盖为单脚本差异化实现。
     */
    suspend fun applyRule(rule: NetworkRule): Result<Unit> {
        // 1. 清理旧规则，保证幂等
        unblockApp(rule.uid).onFailure {
            return Result.failure(it)
        }
        // 2. 统一禁止
        if (rule.blocked) {
            return blockApp(rule.uid)
        }
        // 3. 分组规则（设备不支持时返回 failure，调用方负责提示）
        if (rule.wifiBlocked) {
            setWifiBlocked(rule.uid, true).onFailure {
                return Result.failure(it)
            }
        }
        if (rule.mobileBlocked) {
            setMobileBlocked(rule.uid, true).onFailure {
                return Result.failure(it)
            }
        }
        return Result.success(Unit)
    }

    /**
     * 批量应用多条规则（开机恢复等场景），按包名逐条汇报成功/失败。
     * 默认实现逐条调用 [applyRule]；IptablesController 覆盖为单脚本批处理实现。
     */
    suspend fun applyRules(rules: List<NetworkRule>): Map<String, Result<Unit>> {
        val results = mutableMapOf<String, Result<Unit>>()
        for (rule in rules) {
            results[rule.packageName] = applyRule(rule)
        }
        return results
    }
}

/** 单个 UID 在系统中的规则详情（批量查询用）。 */
data class UidRuleDetail(
    val uid: Int,
    val unifiedBlocked: Boolean,
    val ifaces: Set<String>
) {
    val hasAnyRule: Boolean get() = unifiedBlocked || ifaces.isNotEmpty()

    /** 按接口名前缀判断（与 IptablesController.detectTransports 的分类逻辑一致）。 */
    private fun isWifiIface(n: String) =
        n.startsWith("wlan") || n.startsWith("wlx") || n == "wifi0"
    private fun isMobileIface(n: String) =
        MOBILE_IFACE_PREFIXES.any { n.startsWith(it) }

    val wifiBlockedOnly: Boolean get() =
        !unifiedBlocked && ifaces.isNotEmpty() &&
            ifaces.all { isWifiIface(it) }
    val mobileBlockedOnly: Boolean get() =
        !unifiedBlocked && ifaces.isNotEmpty() &&
            ifaces.all { isMobileIface(it) }

    /** 任一 Wi-Fi 接口上有分组规则（不含统一规则；与旧 isWifiBlocked() 语义一致）。 */
    val wifiBlocked: Boolean get() = ifaces.any { isWifiIface(it) }
    /** 任一移动数据接口上有分组规则（不含统一规则；与旧 isMobileBlocked() 语义一致）。 */
    val mobileBlocked: Boolean get() = ifaces.any { isMobileIface(it) }

    companion object {
        /** 移动数据接口名前缀（companion 常量，避免每次判断都新建 list）。 */
        internal val MOBILE_IFACE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "wwan", "v4-rmnet")
    }
}
