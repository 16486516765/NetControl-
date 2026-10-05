package com.limao.netcontrol

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Process
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.repository.AppRepository
import com.limao.netcontrol.repository.RuleRepository
import com.limao.netcontrol.repository.SettingsRepository
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** 简单的 Service Locator（无 DI 框架，保持依赖最小）。 */
class NetControlApp : Application() {

    lateinit var privilegeManager: PrivilegeManager
        private set
    lateinit var appRepository: AppRepository
        private set
    lateinit var ruleRepository: RuleRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var session: NetControlSession
        private set

    /** 上次启动是否崩溃（诊断用）。 */
    var crashedLastRun: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashReporter()
        if (crashedLastRun) {
            // 上次崩溃：直接展示崩溃报告，不再执行业务初始化（避免重复崩溃盖住日志）。
            startActivity(
                Intent(this, CrashReportActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            return
        }
        privilegeManager = PrivilegeManager(this)
        appRepository = AppRepository(this)
        ruleRepository = RuleRepository(this)
        settingsRepository = SettingsRepository(this)
        session = NetControlSession(
            privilegeManager, appRepository, ruleRepository, settingsRepository
        )
    }

    private fun installCrashReporter() {
        val crashFile = File(filesDir, "crash.log")
        if (crashFile.exists()) crashedLastRun = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val sb = StringBuilder()
                sb.appendLine("MANUFACTURER=" + Build.MANUFACTURER)
                sb.appendLine("MODEL=" + Build.MODEL)
                sb.appendLine("API=" + Build.VERSION.SDK_INT)
                sb.appendLine("THREAD=" + thread.name)
                sb.appendLine("STACK=")
                sb.append(sw.toString())
                crashFile.writeText(sb.toString())
            } catch (e2: Exception) {
                // 忽略日志写入失败
            }
            if (prev != null) prev.uncaughtException(thread, e)
            else Process.killProcess(Process.myPid())
        }
    }

    /** 用户在崩溃报告页点"清除并重进"后调用。 */
    fun clearCrashLog() {
        File(filesDir, "crash.log").delete()
        crashedLastRun = false
    }
}
