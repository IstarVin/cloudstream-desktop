package desktop.app.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import desktop.app.app
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data class SearchRoute(val q: String = "")
@Serializable data object LibraryRoute
@Serializable data object SettingsRoute
@Serializable data class SectionRoute(val provider: String, val section: Int, val row: String, val name: String)
@Serializable data class TitleRoute(val provider: String, val url: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark: light status and navigation bar icons.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val api = context.app.api
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(api.server != null) }
    var pairMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        api.unauthorized.collect {
            pairMessage = "The desktop no longer accepts this device's pairing. Pair it again from desktop Settings."
            paired = false
        }
    }

    if (!paired) {
        PairScreen(message = pairMessage) { config ->
            api.server = config
            scope.launch { context.app.prefs.setServer(config) }
            context.app.cast.reconnect()
            pairMessage = null
            paired = true
        }
    } else {
        MainScaffold(onUnpair = {
            api.server = null
            scope.launch { context.app.prefs.setServer(null) }
            context.app.cast.stop()
            paired = false
        })
    }
}

private data class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val route: Any)

@Composable
private fun MainScaffold(onUnpair: () -> Unit) {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("Home", Icons.Outlined.Home, Icons.Filled.Home, HomeRoute),
        Tab("Search", Icons.Outlined.Search, Icons.Filled.Search, SearchRoute()),
        Tab("Library", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary, LibraryRoute),
        Tab("Settings", Icons.Outlined.Settings, Icons.Filled.Settings, SettingsRoute),
    )
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    fun open(route: Any) {
        if (nav.currentDestination?.hasRoute(route::class) == true) return
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val toTitle: (String, String) -> Unit = { provider, url -> nav.navigate(TitleRoute(provider, url)) }
    val tv = LocalTv.current
    val initialFocus = remember { FocusRequester() }
    InitialTvFocus(initialFocus)
    val wide = tv || LocalWindowInfo.current.containerSize.width >= with(LocalDensity.current) { 600.dp.roundToPx() }
    val slideDistance = with(LocalDensity.current) { 32.dp.roundToPx() }
    val forwardOffset = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -slideDistance else slideDistance
    val edgeMargin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val motion = remember(forwardOffset, edgeMargin) { NavigationMotion(forwardOffset, edgeMargin) }

    Scaffold(
        bottomBar = {
            if (!wide) Column {
                HorizontalDivider(color = Palette.line)
                NavigationBar(containerColor = Palette.bg, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        val selected = dest?.hasRoute(tab.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { open(tab.route) },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.accent, selectedTextColor = Palette.accent,
                                indicatorColor = Palette.accentSoft,
                                unselectedIconColor = Palette.muted, unselectedTextColor = Palette.muted,
                            ),
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .then(if (tv) Modifier.padding(horizontal = 24.dp, vertical = 12.dp) else Modifier)) {
            if (wide) NavigationRail(
                modifier = Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                header = {
                    Box(Modifier.padding(vertical = 20.dp).size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Palette.accent, modifier = Modifier.size(28.dp))
                    }
                },
            ) {
                tabs.forEach { tab ->
                    NavigationRailItem(
                        modifier = Modifier.then(if (tab == tabs.first()) Modifier.focusRequester(initialFocus) else Modifier).tvFocusIndicator(),
                        selected = dest?.hasRoute(tab.route::class) == true,
                        onClick = { open(tab.route) },
                        icon = { Icon(if (dest?.hasRoute(tab.route::class) == true) tab.selectedIcon else tab.icon, null) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Palette.accent, selectedTextColor = Palette.accent,
                            indicatorColor = Palette.accentSoft,
                            unselectedIconColor = Palette.muted, unselectedTextColor = Palette.muted,
                        ),
                        label = { Text(tab.label) },
                    )
                }
            }
            if (wide) VerticalDivider(color = Palette.line)
            // Peer tabs crossfade; details move along a shared axis, reversed for Back. A back gesture previews the
            // pop with Android's predictive-back motion (the detail shrinks away) and the same motion commits it.
            val pushes = { d: NavDestination? -> d?.hasRoute<TitleRoute>() == true || d?.hasRoute<SectionRoute>() == true }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                CompositionLocalProvider(LocalCatalogLayout provides CatalogLayout(expanded = maxWidth >= 720.dp, television = tv)) {
                    Column(Modifier.widthIn(max = 1400.dp).fillMaxSize().padding(horizontal = if (maxWidth >= 900.dp) 20.dp else 0.dp)) {
                        BrowserChecks()
                        NavHost(
                            nav, startDestination = HomeRoute, modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().focusGroup(),
                            enterTransition = { motion.enter(detail = pushes(targetState.destination)) },
                            exitTransition = { motion.exit(detail = pushes(targetState.destination)) },
                            popEnterTransition = { motion.enter(detail = pushes(initialState.destination), back = true) },
                            popExitTransition = { motion.exit(detail = pushes(initialState.destination), back = true) },
                            predictivePopEnterTransition = { motion.predictiveEnter(detail = pushes(initialState.destination)) },
                            predictivePopExitTransition = { edge -> motion.predictiveExit(detail = pushes(initialState.destination), swipeEdge = edge) },
                        ) {
                            composable<HomeRoute> {
                                HomeScreen(
                                    onTitle = toTitle,
                                    onSection = { p, s, row, name -> nav.navigate(SectionRoute(p, s, row, name)) },
                                    onManage = { open(SettingsRoute) },
                                )
                            }
                            composable<SearchRoute> { SearchScreen(initial = it.toRoute<SearchRoute>().q, onTitle = toTitle) }
                            composable<LibraryRoute> { LibraryScreen(onTitle = toTitle, onBrowse = { open(HomeRoute) }) }
                            composable<SettingsRoute> { SettingsScreen(onUnpair = onUnpair) }
                            composable<SectionRoute> {
                                val r = it.toRoute<SectionRoute>()
                                SectionScreen(r.provider, r.section, r.row, r.name, onTitle = toTitle, onBack = { nav.popBackStack() })
                            }
                            composable<TitleRoute> {
                                val r = it.toRoute<TitleRoute>()
                                TitleScreen(r.provider, r.url, onTitle = toTitle, onBack = { nav.popBackStack() })
                            }
                        }
                        // The remote for the device this one plays on, like the web UI's now-playing bar.
                        NowPlayingBar(onTitle = toTitle)
                    }
                }
            }
        }
    }
}
