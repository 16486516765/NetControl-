package com.limao.netcontrol.data

/** 设备上安装的应用信息。 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val isSystemApp: Boolean
)
