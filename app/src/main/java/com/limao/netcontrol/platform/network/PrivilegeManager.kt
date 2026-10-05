package com.limao.netcontrol.platform.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * 特权检测与控制器选择：Root > Shizuku > 无特权。
 *
 * - Root 检测：尝试 `su -c id`，输出包含 uid=0 才算可用（缓存结果）。
 * - Shizuku 检测：是否安装（包名存在）/ binder 是否存活 / 是否已授权。
 */
class PrivilegeManager(private val context: Context) {

    sealed interface Privilege {
        data object Detecting : Privilege
        data class Root(val usable: Boolean) : Privilege
        data class Shizuku(
            val installed: Boolean,
            val running: Boolean,
            val authorized: Boolean
        ) : Privilege
        data object None : Privilege
    }

    private var cachedRootUsable: Boolean? = null

    /** 检测 Root 是否可用（带缓存，避免重复弹 su 授权框）。 */
    suspend fun isRootUsable(): Boolean {
        cachedRootUsable?.let { return it }
        return try {
            val r = RootShellExecutor().exec("id", timeoutMs = 10000)
            val usable = r.success && r.stdout.contains("uid=0")
            Log.i(TAG, "root usable=$usable stdout=${r.stdout} stderr=${r.stderr}")
            cachedRootUsable = usable
            usable
        } catch (e: Exception) {
            Log.e(TAG, "root check failed", e)
            cachedRootUsable = false
            false
        }
    }

    fun isShizukuInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isShizukuRunning(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

    fun isShizukuAuthorized(): Boolean =
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

    /** 按 Root > Shizuku > 无特权 的优先级检测。 */
    suspend fun detectPrivilege(): Privilege {
        if (isRootUsable()) return Privilege.Root(usable = true)
        val installed = isShizukuInstalled()
        val running = if (installed) isShizukuRunning() else false
        val authorized = if (running) isShizukuAuthorized() else false
        Log.i(TAG, "shizuku installed=$installed running=$running authorized=$authorized")
        if (installed) return Privilege.Shizuku(installed, running, authorized)
        return Privilege.None
    }

    /** 根据检测结果创建对应的控制器。 */
    fun createController(privilege: Privilege): NetworkController {
        return when (privilege) {
            is Privilege.Root -> if (privilege.usable) RootNetworkController()
                else UnsupportedNetworkController()
            is Privilege.Shizuku ->
                if (privilege.running && privilege.authorized) ShizukuNetworkController()
                else UnsupportedNetworkController()
            else -> UnsupportedNetworkController()
        }
    }

    /** 清除 Root 缓存（用于设置页“重新检测”）。 */
    fun clearCache() {
        cachedRootUsable = null
    }

    companion object {
        private const val TAG = "PrivilegeManager"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
