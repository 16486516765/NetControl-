package com.limao.netcontrol.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.limao.netcontrol.data.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 扫描设备已安装应用。 */
class AppRepository(private val context: Context) {

    /** 应用标签并发上限：loadLabel 是 Binder 调用，无上限并发反而增加调度与服务端争用。 */
    private val labelSemaphore = Semaphore(6)

    // 应用列表缓存：首次加载后复用，安装/卸载/更新时由 PackageChangeReceiver
    // 或 AppListViewModel.invalidateCache() 失效。generation 防止“失效后
    // 旧加载结果又写回缓存”的竞态。
    private val cacheMutex = Mutex()
    @Volatile
    private var cachedApps: List<AppInfo>? = null
    private var cacheGeneration = 0

    /**
     * 加载全部应用（排除自身），按名称排序。IO 线程执行。
     * 结果缓存，安装变更时失效；应用列表、规则页和详情页复用同一份缓存。
     */
    suspend fun loadApps(): List<AppInfo> = cacheMutex.withLock {
        cachedApps?.let { return@withLock it }
        val generation = cacheGeneration
        val result = loadAppsUncached()
        // 只有缓存未被失效过才写回，避免旧扫描结果覆盖失效后的新状态
        if (generation == cacheGeneration) cachedApps = result
        result
    }

    /** 使应用列表缓存失效（安装/卸载/更新时调用），下次 loadApps() 重新扫描。 */
    fun invalidateAppsCache() {
        cacheGeneration++
        cachedApps = null
    }

    /** 按包名取单个应用信息（详情页 fallback 用，不全量扫描）。 */
    suspend fun loadApp(packageName: String): AppInfo? = withContext(Dispatchers.IO) {
        // 先查缓存，命中直接返回
        cachedApps?.find { it.packageName == packageName }?.let { return@withContext it }
        val pm = context.packageManager
        try {
            val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            toAppInfo(ai, pm)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private suspend fun loadAppsUncached(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        // loadLabel 是 Binder 调用，有界并发（6）避免数百个协程同时冲击 Binder
        val deferred = installed
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { ai ->
                async {
                    labelSemaphore.withPermit {
                        toAppInfo(ai, pm)
                    }
                }
            }
            .toList()
        deferred.awaitAll().sortedBy { it.appName.lowercase() }
    }

    private fun toAppInfo(ai: ApplicationInfo, pm: PackageManager): AppInfo {
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return AppInfo(
            packageName = ai.packageName,
            appName = ai.loadLabel(pm)?.toString() ?: ai.packageName,
            uid = ai.uid,
            isSystemApp = isSystem
        )
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
}
