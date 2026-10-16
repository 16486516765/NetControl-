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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.limao.netcontrol.data.AppInfo
import com.limao.netcontrol.data.NetworkRule
import com.limao.netcontrol.repository.SettingsRepository
import com.limao.netcontrol.ui.UiMode
import com.limao.netcontrol.ui.detail.AppDetailScreen
import com.limao.netcontrol.ui.glass.BottomTab
import com.limao.netcontrol.ui.glass.GlassBottomBar
import com.limao.netcontrol.ui.glass.GlassScaffold
import com.limao.netcontrol.ui.home.HomeScreen
import com.limao.netcontrol.ui.liquid.LiquidBottomBar
import com.limao.netcontrol.ui.rules.RulesScreen
import com.limao.netcontrol.ui.settings.SettingsScreen
import com.limao.netcontrol.ui.theme.NetControlTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import com.limao.netcontrol.viewmodel.AppDetailViewModel
import com.limao.netcontrol.viewmodel.AppListViewModel
import com.limao.netcontrol.viewmodel.MainViewModel
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
    private val settingsViewModel: SettingsViewModel by viewModels { appVmFactory }

    // 上一次 Shizuku 授权轮询 Job：新授权结果到来时取消旧轮询，避免重叠
    private var shizukuPollJob: Job? = null
    // 上一次回到前台的轻量权限刷新 Job：下次恢复时取消，避免多个检测循环重叠
    private var resumeDetectJob: Job? = null
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // 授权通过：轮询等系统同步完成，最多 5 次 x 500ms
                    Log.i(TAG, "shizuku granted, polling for auth state")
                    shizukuPollJob?.cancel()
                    shizukuPollJob = lifecycleScope.launch {
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
        // 回到前台时轻量刷新一次权限（不跑完整规则校验，不做 5 轮重检）。
        // 撤销授权后系统同步有延迟，先等 500ms 再做一次检测；
        // Job 保存下来，下次恢复/页面销毁时取消，避免多个检测循环重叠。
        val lifecycleOwner = LocalLifecycleOwner.current
        val firstResume = remember { mutableStateOf(true) }
        // resume 检测 Job 存在组合作用域外（Activity 字段），跨重组保持可取消
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (firstResume.value) {
                        firstResume.value = false
                    } else {
                        resumeDetectJob?.cancel()
                        resumeDetectJob = lifecycleScope.launch {
                            delay(500)
                            mainViewModel.refreshPrivilege()
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                resumeDetectJob?.cancel()
            }
        }

        val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle(initialValue = "system")
        val darkTheme = when (themeMode) {
            SettingsRepository.ThemeMode.LIGHT -> false
            SettingsRepository.ThemeMode.DARK -> true
            else -> isSystemInDarkTheme()
        }
        // 界面风格（抄 KSU 的 UiMode）：MIUI = 液态玻璃底栏 + 弹簧转场，谷歌 = Material 底栏 + tween 淡入淡出
        val uiModeValue by mainViewModel.uiMode.collectAsStateWithLifecycle(initialValue = UiMode.DEFAULT_VALUE)
        val uiMode = UiMode.fromValue(uiModeValue)

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

        NetControlTheme(uiMode = uiMode, darkTheme = darkTheme) {
            // 液态玻璃底栏的采样背景（抄 KSU MainActivity 的 backdrop 接线）：
            // backdrop 采样当前页面内容，底栏的 lens/vibrancy 在它上面做折射。
            // 只在 MIUI 模式下生效，谷歌模式不采样。
            // 注意：颜色必须在 lambda 外读出——rememberLayerBackdrop 的 lambda 是
            // DrawScope 绘制块（非 @Composable），不能在里面读 MaterialTheme。
            val backdropSurfaceColor = MaterialTheme.colorScheme.surface
            val backdrop = rememberLayerBackdrop {
                drawRect(backdropSurfaceColor)
                drawContent()
            }
            GlassScaffold {
                Box(Modifier.fillMaxSize()) {
                    // 页面转场：按界面风格分支。
                    // MIUI：弹簧 + 轻微缩放（Miuix 手感，带一点回弹）；
                    // 谷歌：tween 220ms 淡入淡出（保持原有）。
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            if (uiMode == UiMode.Miuix) {
                                val springSpec = spring<Float>(
                                    stiffness = Spring.StiffnessMedium,
                                    dampingRatio = 0.85f
                                )
                                (fadeIn(animationSpec = springSpec) +
                                    scaleIn(
                                        initialScale = 0.98f,
                                        animationSpec = springSpec
                                    )) togetherWith
                                    (fadeOut(animationSpec = springSpec) +
                                        scaleOut(
                                            targetScale = 0.98f,
                                            animationSpec = springSpec
                                        ))
                            } else {
                                fadeIn(animationSpec = tween(durationMillis = 220)) togetherWith
                                    fadeOut(animationSpec = tween(durationMillis = 220))
                            }
                        },
                        label = "screenTransition",
                        modifier = Modifier
                            .fillMaxSize()
                            // MIUI 模式：页面内容作为液态玻璃底栏的采样层（抄 KSU 的 pager 接线）
                            .then(
                                if (uiMode == UiMode.Miuix) Modifier.layerBackdrop(backdrop)
                                else Modifier
                            )
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
                        val tabs = listOf(
                            BottomTab("应用", Icons.Filled.List),
                            BottomTab("规则", Icons.Filled.Shield),
                            BottomTab("设置", Icons.Filled.Settings)
                        )
                        val navigateToTab: (Int) -> Unit = { index ->
                            backStack.clear()
                            backStack.add(
                                when (index) {
                                    0 -> Screen.Home
                                    1 -> Screen.Rules
                                    else -> Screen.Settings
                                }
                            )
                        }
                        if (uiMode == UiMode.Miuix) {
                            // MIUI 模式：液态玻璃悬浮底栏（移植自 KSU 的 FloatingBottomBar）
                            LiquidBottomBar(
                                tabs = tabs,
                                selectedIndex = selectedIndex,
                                isDark = darkTheme,
                                backdrop = backdrop,
                                onSelect = navigateToTab,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                            )
                        } else {
                            // 谷歌模式：现有 Material 3 底栏
                            GlassBottomBar(
                                tabs = tabs,
                                selectedIndex = selectedIndex,
                                onSelect = navigateToTab,
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
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 1001
    }
}
