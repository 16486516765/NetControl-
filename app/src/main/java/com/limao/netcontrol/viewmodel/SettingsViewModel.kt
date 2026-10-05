package com.limao.netcontrol.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
    val customBackgroundPath = settings.customBackgroundPathFlow
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

    fun clearCustomBackground() = viewModelScope.launch {
        settings.setCustomBackgroundPath("")
    }

    /** 用户从相册选择背景图：压缩后存到应用内部并持久化路径。 */
    fun importBackgroundImage(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                copyBackgroundToInternal(uri)
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    private suspend fun copyBackgroundToInternal(uri: Uri): Boolean {
        val context = getApplication<Application>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bmp = BitmapFactory.decodeStream(input) ?: return false
            // 最长边压缩到 1440px，节省空间且足够做背景
            val maxSide = 1440
            val longest = maxOf(bmp.width, bmp.height).toFloat()
            val scaled = if (longest > maxSide) {
                val k = maxSide / longest
                Bitmap.createScaledBitmap(
                    bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true
                )
            } else bmp
            val dir = File(context.filesDir, "backgrounds").apply { mkdirs() }
            val out = File(dir, "custom_bg.jpg")
            FileOutputStream(out).use { fos ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 88, fos)) return false
            }
            if (scaled !== bmp) bmp.recycle()
            settings.setCustomBackgroundPath(out.absolutePath)
            return true
        }
        return false
    }

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
