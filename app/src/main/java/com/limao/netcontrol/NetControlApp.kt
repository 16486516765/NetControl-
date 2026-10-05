package com.limao.netcontrol

import android.app.Application
import com.limao.netcontrol.platform.network.PrivilegeManager
import com.limao.netcontrol.repository.AppRepository
import com.limao.netcontrol.repository.RuleRepository
import com.limao.netcontrol.repository.SettingsRepository

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

    override fun onCreate() {
        super.onCreate()
        privilegeManager = PrivilegeManager(this)
        appRepository = AppRepository(this)
        ruleRepository = RuleRepository(this)
        settingsRepository = SettingsRepository(this)
        session = NetControlSession(
            privilegeManager, appRepository, ruleRepository, settingsRepository
        )
    }
}
