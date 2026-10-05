package com.limao.netcontrol.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limao.netcontrol.NetControlApp
import com.limao.netcontrol.NetControlSession
import com.limao.netcontrol.repository.SettingsRepository

/** 主 ViewModel：暴露全局会话与主题设置。 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val netApp = app as NetControlApp

    val session: NetControlSession = netApp.session
    val settings: SettingsRepository = netApp.settingsRepository

    val privilege = session.privilege
    val splitSupported = session.splitSupported
    val startupVerified = session.startupVerified
    val driftFixed = session.driftFixed
    val pendingRestore = settings.pendingRestoreFlow
    val themeMode = settings.themeModeFlow

    init {
        session.detect(viewModelScope)
    }

    /** 设置页“重新检测权限”。 */
    fun redetect() {
        session.clearPrivilegeCache()
        session.detect(viewModelScope)
    }
}
