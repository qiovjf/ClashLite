package com.clashlite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clashlite.ClashViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.HazeMaterials

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun ClashNav(vm: ClashViewModel) {
    val navController = rememberNavController()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val hazeState = remember { HazeState() }

    val tabs = listOf(
        Tab("dashboard", "仪表盘", Icons.Filled.Dashboard),
        Tab("proxies", "代理", Icons.Filled.SwapHoriz),
        Tab("connections", "连接", Icons.Filled.Lan),
        Tab("profiles", "订阅", Icons.Filled.Extension),
        Tab("theme", "主题", Icons.Filled.Palette),
        Tab("settings", "设置", Icons.Filled.Settings),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val glass = theme.glassNavBar

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (glass) {
                GlassNavBar(hazeState, tabs, currentRoute) { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            } else {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 玻璃模式下内容延伸到导航栏下方（只保留状态栏内边距），模糊效果才能透出内容
        val contentModifier = if (glass) {
            Modifier.padding(top = padding.calculateTopPadding()).hazeSource(hazeState)
        } else {
            Modifier.padding(padding)
        }

        androidx.compose.runtime.CompositionLocalProvider(LocalGlassNav provides glass) {
            NavHost(
                navController = navController,
                startDestination = "dashboard",
                modifier = contentModifier,
            ) {
            composable("dashboard") { DashboardScreen(vm) }
            composable("proxies") { ProxiesScreen(vm) }
            composable("connections") { ConnectionsScreen(vm) }
            composable("profiles") { ProfilesScreen(vm) }
                composable("theme") { ThemeScreen(vm) }
                composable("settings") { SettingsScreen(vm, onOpenCoreConfig = { navController.navigate("coreconfig") }) }
                composable("coreconfig") { CoreConfigScreen(vm) { navController.popBackStack() } }
            }
        }
    }
}

/** 玻璃导航栏开启时，页面内容需要预留底部空间 */
val LocalGlassNav = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 液态玻璃风格导航栏（iOS 风格）：
 * 通过 Haze 对导航栏下方滚动内容做实时模糊（RenderEffect，Android 12+），
 * 叠加半透明表面色与顶部高光描边；Android 12 以下自动退化为半透明。
 */
@Composable
private fun GlassNavBar(
    hazeState: HazeState,
    tabs: List<Tab>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val glassShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(glassShape)
            .hazeChild(
                state = hazeState,
                style = HazeMaterials.ultraThin(containerColor = surface.copy(alpha = 0.72f)),
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    startY = 0f,
                    endY = 110f,
                )
            ),
    ) {
        // 顶部 1px 高光描边 + 底部柔和暗边，模拟玻璃厚度
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.65f),
                            onSurface.copy(alpha = 0.15f),
                            Color.Transparent,
                        )
                    )
                ),
        )
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(tab.route) },
                    icon = {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(width = 44.dp, height = 30.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(primary.copy(alpha = 0.25f)),
                                contentAlignment = androidx.compose.ui.Alignment.Center,
                            ) {
                                Icon(tab.icon, contentDescription = tab.label, tint = primary)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = tab.label, tint = onSurface.copy(alpha = 0.72f))
                        }
                    },
                    label = { Text(tab.label, color = onSurface.copy(alpha = 0.85f)) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
                )
            }
        }
    }
}
