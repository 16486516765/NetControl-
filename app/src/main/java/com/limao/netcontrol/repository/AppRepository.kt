package com.limao.netcontrol.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.limao.netcontrol.data.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/** 扫描设备已安装应用。 */
class AppRepository(private val context: Context) {

    /** 加载全部应用（排除自身），按名称排序。IO 线程执行。 */
    suspend fun loadApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        // 性能优化：loadLabel 是 Binder 调用，并行化加速 500 个应用的加载
        val deferred = installed
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { ai ->
                async {
                    val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    AppInfo(
                        packageName = ai.packageName,
                        appName = ai.loadLabel(pm)?.toString() ?: ai.packageName,
                        uid = ai.uid,
                        isSystemApp = isSystem
                    )
                }
            }
            .toList()
        deferred.awaitAll().sortedBy { it.appName.lowercase() }
    }

    /** 按包名取 UID（应用更新后 UID 可能变化）。IO 线程执行。 */
    suspend fun getUid(packageName: String): Int? = withContext(Dispatchers.IO) {
        return@withContext try {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            ai.uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** 是否仍已安装。 */
    suspend fun isInstalled(packageName: String): Boolean = getUid(packageName) != null

    fun loadIcon(packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }
}
