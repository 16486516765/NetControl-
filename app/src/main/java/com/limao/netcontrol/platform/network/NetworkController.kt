package com.limao.netcontrol.platform.network

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
     * 一次性读取全部 UID 的规则详情（统一/分组）。
     * 默认返回空 map；IptablesController 提供真正实现。
     */
    suspend fun getAllUidRules(): Map<Int, UidRuleDetail> = emptyMap()
}

/** 单个 UID 在系统中的规则详情（批量查询用）。 */
data class UidRuleDetail(
    val uid: Int,
    val unifiedBlocked: Boolean,
    val ifaces: Set<String>
) {
    val hasAnyRule: Boolean get() = unifiedBlocked || ifaces.isNotEmpty()
}
