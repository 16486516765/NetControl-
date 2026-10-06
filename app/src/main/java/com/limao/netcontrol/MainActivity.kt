package com.limao.netcontrol

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.limao.netcontrol.data.AppInfo
import com.limao.netcontrol.data.NetworkRule
import com.limao.netcontrol.repository.SettingsRepository
import com.limao.netcontrol.ui.detail.AppDetailScreen
import com.limao.netcontrol.ui.glass.BottomTab
import com.limao.netcontrol.ui.glass.GlassBottomBar
import com.limao.netcontrol.ui.glass.GlassScaffold
import com.limao.netcontrol.ui.home.HomeScreen
import com.limao.netcontrol.ui.rules.RulesScreen
import com.limao.netcontrol.ui.settings.SettingsScreen
import com.limao.netcontrol.ui.theme.NetControlTheme
import com.limao.netcontrol.viewmodel.AppDetailViewModel
import com.limao.netcontrol.viewmodel.AppListViewModel
import com.limao.netcontrol.viewmodel.MainViewModel
import com.limao.netcontrol.viewmodel.RulesViewModel
import com.limao.netcontrol.viewmodel.SettingsViewModel
import rikka.shizuku.Shizuku

sealed interface Screen {
    data object Home : Screen
    data object Rules : Screen
    data object Settings : Screen
    data class Detail(
        val pkg: String,
        val info: AppInfo?,
        val rule: NetworkRule?
    ) : Screen
}

class MainActivity : ComponentActivity() {

    /** 所有 AndroidViewModel 子类共用的 Factory（传入 Application，避免默认工厂无参构造崩溃）。 */
    private val appVmFactory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            modelClass.getConstructor(Application::class.java).newInstance(application) as T
    }

    private val mainViewModel: MainViewModel by viewModels { appVmFactory }
    private val appListViewModel: AppListViewModel by viewModels { appVmFactory }
    private val rulesViewModel: RulesViewModel by viewModels { appVmFactory }
    private val settingsViewModel: SettingsViewModel by viewModels { appVmFactory }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // 授权通过：轮询等系统同步完成，最多 5 次 x 500ms
                    Log.i(TAG, "shizuku granted, polling for auth state")
                    lifecycleScope.launch {
                        repeat(5) { attempt ->
                            delay(500)
                            val authorized = try {
                                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                            } catch (_: Exception) { false }
                            Log.i(TAG, "shizuku auth poll ${attempt + 1}/5: $authorized")
                            if (authorized) {
                                mainViewModel.redetect()
                                return@launch
                            }
                        }
                        Log.i(TAG, "shizuku poll timeout, redetect anyway")
                        mainViewModel.redetect()
                    }
                } else {
                    Log.i(TAG, "shizuku denied, re-detect")
                    mainViewModel.redetect()
                }
            }
        }
    private val binderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            Log.i(TAG, "shizuku binder received")
            mainViewModel.redetect()
        }
    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            Log.i(TAG, "shizuku binder dead")
            mainViewModel.redetect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 诊断模式：上次崩溃过则直接退出，由 CrashReportActivity 展示日志。
        if ((application as NetControlApp).crashedLastRun) { finish(); return }
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        setContent {
            NetControlRoot()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    /** 发起 Shizuku 授权请求（结果经 listener 回调后重新检测）。 */
    fun requestShizukuPermission() {
        try {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "request shizuku permission failed", e)
        }
    }

    @Composable
    private fun NetControlRoot() {
        // 问题1修复：回到前台自动重检权限（静默更新UI）
        // 延迟 1 秒等系统同步完权限变更（撤销授权后立即查会拿到旧值）
        val lifecycleOwner = LocalLifecycleOwner.current
        val firstResume = remember { mutableStateOf(true) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (firstResume.value) {
                        firstResume.value = false
                    } else {
                        lifecycleScope.launch {
                            delay(1000)
                            mainViewModel.redetect()
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle(initialValue = "system")
        val darkTheme = when (themeMode) {
            SettingsRepository.ThemeMode.LIGHT -> false
            SettingsRepository.ThemeMode.DARK -> true
            else -> isSystemInDarkTheme()
        }

        val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
        val current = backStack.lastOrNull() ?: Screen.Home

        val detailFactory = remember {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppDetailViewModel(application) as T
                }
            }
        }

        BackHandler(enabled = backStack.size > 1) {
            backStack.removeLastOrNull()
        }

        NetControlTheme(darkTheme) {
            GlassScaffold {
                Box(Modifier.fillMaxSize()) {
                    // 页面转场：淡入淡出。只做透明度动画，开销小。
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(durationMillis = 220)) togetherWith
                                fadeOut(animationSpec = tween(durationMillis = 220))
                        },
                        label = "screenTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { screen ->
                        when (val s = screen) {
                        is Screen.Home -> HomeScreen(
                            mainViewModel = mainViewModel,
                            listViewModel = appListViewModel,
                            onAppClick = { info ->
                                val rule = appListViewModel.rows.value
                                    .find { it.info.packageName == info.packageName }?.rule
                                backStack.add(Screen.Detail(info.packageName, info, rule))
                            },
                            onRequestShizuku = ::requestShizukuPermission
                        )
                        is Screen.Rules -> RulesScreen(
                            listViewModel = appListViewModel,
                            onAppClick = { info ->
                                val rule = appListViewModel.rows.value
                                    .find { it.info.packageName == info.packageName }?.rule
                                backStack.add(Screen.Detail(info.packageName, info, rule))
                            }
                        )
                        is Screen.Settings -> SettingsScreen(
                            mainViewModel = mainViewModel,
                            settingsViewModel = settingsViewModel,
                            onRequestShizuku = ::requestShizukuPermission
                        )
                        is Screen.Detail -> {
                            val detailVm: AppDetailViewModel =
                                viewModel(key = "detail_${s.pkg}", factory = detailFactory)
                            AppDetailScreen(
                                packageName = s.pkg,
                                cachedInfo = s.info,
                                cachedRule = s.rule,
                                detailViewModel = detailVm,
                                mainViewModel = mainViewModel,
                                onBack = { backStack.removeLastOrNull() }
                            )
                        }
                        } // end when
                    } // end AnimatedContent

                    if (current !is Screen.Detail) {
                        val selectedIndex = when (current) {
                            is Screen.Home -> 0
                            is Screen.Rules -> 1
                            else -> 2
                        }
                        GlassBottomBar(
                            tabs = listOf(
                                BottomTab("应用", Icons.Filled.List),
                                BottomTab("规则", Icons.Filled.Shield),
                                BottomTab("设置", Icons.Filled.Settings)
                            ),
                            selectedIndex = selectedIndex,
                            onSelect = { index ->
                                backStack.clear()
                                backStack.add(
                                    when (index) {
                                        0 -> Screen.Home
                                        1 -> Screen.Rules
                                        else -> Screen.Settings
                                    }
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp)
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 1001
    }
}
