package com.xuedi.coder.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xuedi.coder.App
import com.xuedi.coder.R
import com.xuedi.coder.ui.theme.AiCoderTheme
import com.xuedi.coder.vm.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed class NavItem(val route: String, val labelRes: Int, val icon: @Composable () -> Unit) {
    data object Chat : NavItem("chat", R.string.nav_chat, { Icon(Icons.Outlined.Code, null) })
    data object Plugins : NavItem("plugins", R.string.nav_plugins, { Icon(Icons.Outlined.ViewModule, null) })
    data object Settings : NavItem("settings", R.string.nav_settings, { Icon(Icons.Outlined.Settings, null) })
    data object About : NavItem("about", R.string.nav_about, { Icon(Icons.Outlined.Info, null) })
}

val bottomNavItems = listOf(NavItem.Chat, NavItem.Plugins, NavItem.Settings, NavItem.About)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavHost(
    appScope: CoroutineScope,
    requestImportModel: () -> Unit,
    requestImportBackground: () -> Unit,
    chatVm: ChatViewModel = viewModel()
) {
    AiCoderTheme {
        BackgroundContainer {
            val navController = rememberNavController()

            // 背景状态实时读（后面 ThemeStore DataStore 用 collectAsStateWithLifecycle）
            val bg by UiBackground.backgroundUri.collectAsStateWithLifecycle()
            val alpha by UiBackground.alpha.collectAsStateWithLifecycle()

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    ) {
                        val backStack by navController.currentBackStackEntryAsState()
                        val current = backStack?.destination
                        bottomNavItems.forEach { item ->
                            NavigationBarItem(
                                selected = current?.hierarchy?.any { it.route == item.route } == true,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = item.icon,
                                label = { Text(stringResource(item.labelRes)) }
                            )
                        }
                    }
                },
                containerColor = androidx.compose.ui.graphics.Color.Transparent
            ) { inner ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(inner)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = NavItem.Chat.route
                    ) {
                        composable(NavItem.Chat.route) { ChatPage(vm = chatVm) }
                        composable(NavItem.Plugins.route) { PluginsPage(appScope = appScope) }
                        composable(NavItem.Settings.route) {
                            // Toast/错误提示用 App Context（不需要 Activity）
                            val appCtx = App.instance
                            SettingsPage(
                                currentBg = bg,
                                currentAlpha = alpha,
                                // M4=管理层：不再只改 UiBackground 内存态，
                                // 而是调 App.instance.themeStore 写入 DataStore 持久化。
                                // App.kt 里的 Flow 收集会再同步到 UiBackground，所以 UI 同样实时更新。
                                setBg = { uriStr ->
                                    if (uriStr.isNullOrBlank()) {
                                        // 清除背景 → 调 ThemeStore.clearBackground()
                                        appScope.launch {
                                            runCatching { App.instance.themeStore.clearBackground() }
                                        }
                                        return@SettingsPage
                                    }
                                    val uri = android.net.Uri.parse(uriStr)
                                    appScope.launch {
                                        runCatching {
                                            App.instance.themeStore.importBackgroundFromUri(uri)
                                        }.onFailure { t ->
                                            android.widget.Toast.makeText(
                                                appCtx,
                                                "背景导入失败：${t.message}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                setAlpha = { a ->
                                    // 顺便立即更新 UiBackground，避免等 Flow 同步延迟视觉
                                    UiBackground.setAlpha(a)
                                    appScope.launch {
                                        runCatching { App.instance.themeStore.setBackgroundAlpha(a) }
                                    }
                                },
                                requestImportModel = requestImportModel,
                                requestImportBackground = requestImportBackground
                            )
                        }
                        composable(NavItem.About.route) { AboutPage() }
                    }
                }
            }
        }
    }
}
