package com.limao.netcontrol.ui

/**
 * 界面风格（抄 KernelSU 的 UiMode 机制）。
 *
 * - Miuix：MIUI 风格——Miuix 组件 + 液态玻璃底栏 + 弹簧动画。
 * - Material：谷歌风格——纯 Material 3 + tween 淡入淡出。
 *
 * 默认 MIUI（用户要求从谷歌动画切到 MIUI 动画）。
 */
enum class UiMode(val value: String) {
    Miuix("miuix"),
    Material("material");

    companion object {
        fun fromValue(value: String): UiMode = when (value) {
            Material.value -> Material
            else -> Miuix
        }

        val DEFAULT_VALUE = Miuix.value
    }
}
