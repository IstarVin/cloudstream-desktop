package desktop.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import desktop.app.ui.AppTheme
import desktop.app.ui.HomeRefreshContainer
import desktop.app.ui.LocalTv
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeRefreshTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun tvScrollingCannotTriggerPullToRefresh() = checkPullToRefresh(tv = true)

    @Test fun phonesRetainPullToRefresh() = checkPullToRefresh(tv = false)

    private fun checkPullToRefresh(tv: Boolean) {
        var refreshes = 0
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalTv provides tv) {
                    HomeRefreshContainer(isRefreshing = false, onRefresh = { refreshes++ }) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(100) { Text("Title $it") }
                        }
                    }
                }
            }
        }
        // An unconsumed downward scroll at the top must only refresh a touch surface.
        compose.onRoot().performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(if (tv) 0 else 1, refreshes) }
    }
}
