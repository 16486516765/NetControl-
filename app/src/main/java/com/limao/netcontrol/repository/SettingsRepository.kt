package com.limao.netcontrol.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 应用设置持久化（SharedPreferences）。 */
class SettingsRepository(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private val showSystemAppsKey = "show_system_apps"
    private val showBlockedOnlyKey = "show_blocked_only"
    private val sortModeKey = "sort_mode"
    private val bootRestoreKey = "boot_restore"
    private val themeModeKey = "theme_mode"
    private val pendingRestoreKey = "pending_restore"
    private val customBackgroundKey = "custom_background_path"

    private fun booleanFlow(key: String): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == key) trySend(prefs.getBoolean(key, false))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getBoolean(key, false))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun stringFlow(key: String, default: String): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k == key) trySend(prefs.getString(key, default) ?: default)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(prefs.getString(key, default) ?: default)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val showSystemAppsFlow: Flow<Boolean> = booleanFlow(showSystemAppsKey)
    val showBlockedOnlyFlow: Flow<Boolean> = booleanFlow(showBlockedOnlyKey)
    val sortModeFlow: Flow<String> = stringFlow(sortModeKey, SortMode.NAME)
    val bootRestoreFlow: Flow<Boolean> = booleanFlow(bootRestoreKey)
    val themeModeFlow: Flow<String> = stringFlow(themeModeKey, ThemeMode.SYSTEM)
    val pendingRestoreFlow: Flow<Boolean> = booleanFlow(pendingRestoreKey)
    val customBackgroundPathFlow: Flow<String> = stringFlow(customBackgroundKey, "")

    suspend fun setShowSystemApps(v: Boolean) {
        prefs.edit().putBoolean(showSystemAppsKey, v).apply()
    }
    suspend fun setShowBlockedOnly(v: Boolean) {
        prefs.edit().putBoolean(showBlockedOnlyKey, v).apply()
    }
    suspend fun setSortMode(v: String) {
        prefs.edit().putString(sortModeKey, v).apply()
    }
    suspend fun setBootRestore(v: Boolean) {
        prefs.edit().putBoolean(bootRestoreKey, v).apply()
    }
    suspend fun setThemeMode(v: String) {
        prefs.edit().putString(themeModeKey, v).apply()
    }
    suspend fun setPendingRestore(v: Boolean) {
        prefs.edit().putBoolean(pendingRestoreKey, v).apply()
    }
    suspend fun setCustomBackgroundPath(v: String) {
        prefs.edit().putString(customBackgroundKey, v).apply()
    }

    suspend fun isBootRestoreEnabled(): Boolean = bootRestoreFlow.first()

    object SortMode {
        const val NAME = "name"
        const val PACKAGE = "package"
        const val STATUS = "status"
    }

    object ThemeMode {
        const val SYSTEM = "system"
        const val LIGHT = "light"
        const val DARK = "dark"
    }
}
