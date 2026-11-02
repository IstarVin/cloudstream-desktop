package desktop.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
            pairMessage = "The desktop no longer accepts this phone's pairing. Scan its code again."
            paired = false
        }
    }

    if (!paired) {
        PairScreen(message = pairMessage) { config ->
            api.server = config
            scope.launch { context.app.prefs.setServer(config) }
            pairMessage = null
            paired = true
        }
    } else {
        MainScaffold(onUnpair = {
            api.server = null
            scope.launch { context.app.prefs.setServer(null) }
            paired = false
        })
    }
}

private data class Tab(val label: String, val icon: ImageVector, val route: Any)

@Composable
private fun MainScaffold(onUnpair: () -> Unit) {
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("Home", Icons.Filled.Home, HomeRoute),
        Tab("Search", Icons.Filled.Search, SearchRoute()),
        Tab("Library", Icons.Filled.VideoLibrary, LibraryRoute),
        Tab("Settings", Icons.Filled.Settings, SettingsRoute),
    )
    val entry by nav.currentBackStackEntryAsState()
    val dest = entry?.destination
    fun open(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    val toTitle: (String, String) -> Unit = { provider, url -> nav.navigate(TitleRoute(provider, url)) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = dest?.hasRoute(tab.route::class) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { open(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> {
                HomeScreen(
                    onTitle = toTitle,
                    onSection = { p, s, row, name -> nav.navigate(SectionRoute(p, s, row, name)) },
                )
            }
            composable<SearchRoute> { SearchScreen(initial = it.toRoute<SearchRoute>().q, onTitle = toTitle) }
            composable<LibraryRoute> { LibraryScreen(onTitle = toTitle) }
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
    }
}
