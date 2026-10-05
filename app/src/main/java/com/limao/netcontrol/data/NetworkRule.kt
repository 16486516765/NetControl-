package com.limao.netcontrol.data

/**
 * 单个应用的网络规则（持久化）。
 *
 * blocked        统一禁止联网
 * wifiBlocked    仅禁止 Wi-Fi（需设备支持分组控制）
 * mobileBlocked  仅禁止移动数据（需设备支持分组控制）
 */
data class NetworkRule(
    val packageName: String,
    val uid: Int,
    val blocked: Boolean = false,
    val wifiBlocked: Boolean = false,
    val mobileBlocked: Boolean = false
) {
    val hasAnyRestriction: Boolean
        get() = blocked || wifiBlocked || mobileBlocked
}
