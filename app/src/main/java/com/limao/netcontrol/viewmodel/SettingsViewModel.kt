package com.limao.netcontrol.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.limao.netcontrol.NetControlApp
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val netApp = app as NetControlApp
    val session = netApp.session
    private val settings = netApp.settingsRepository
    private val rules = netApp.ruleRepository

    val showSystemApps = settings.showSystemAppsFlow
    val showBlockedOnly = settings.showBlockedOnlyFlow
    val sortMode = settings.sortModeFlow
    val bootRestore = settings.bootRestoreFlow
    val themeMode = settings.themeModeFlow
    val pendingRestore = settings.pendingRestoreFlow
    val privilege = session.privilege

    fun setShowSystemApps(v: Boolean) = viewModelScope.launch {
        settings.setShowSystemApps(v)
    }
    fun setShowBlockedOnly(v: Boolean) = viewModelScope.launch {
        settings.setShowBlockedOnly(v)
    }
    fun setSortMode(v: String) = viewModelScope.launch { settings.setSortMode(v) }
    fun setBootRestore(v: Boolean) = viewModelScope.launch { settings.setBootRestore(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { settings.setThemeMode(v) }

    fun redetectPrivilege() {
        session.clearPrivilegeCache()
        session.detect(viewModelScope)
    }

    /** 手动恢复开机未恢复的规则。 */
    fun restorePendingRules(onResult: (Result<Pair<Int, Int>>) -> Unit) {
        viewModelScope.launch {
            onResult(session.restorePendingRules())
        }
    }

    fun dismissPending() {
        viewModelScope.launch { settings.setPendingRestore(false) }
    }
}
