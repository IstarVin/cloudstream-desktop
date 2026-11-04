package com.istarvin.cloudstream

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.istarvin.cloudstream.ui.AppTheme
import com.istarvin.cloudstream.ui.SectionScreen
import com.istarvin.cloudstream.ui.SectionViewModel
import desktop.api.HomePage
import desktop.api.HomeRow
import desktop.api.SearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class SectionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningFromTitleRetainsSectionPagesAndScrollPosition() {
        lateinit var original: SectionViewModel
        lateinit var current: SectionViewModel
        var visits = 0
        compose.setContent {
            AppTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "section") {
                    composable("section") {
                        val vm: SectionViewModel = viewModel()
                        current = vm
                        if (visits == 0) {
                            original = vm
                            // Two completed pages, with enough posters to scroll well beyond page one.
                            repeat(2) { page ->
                                vm.pager.pages.add(HomePage(listOf(HomeRow("Popular", false,
                                    (page * 60 until (page + 1) * 60).map { index ->
                                        SearchItem(provider = "Source", name = "Title $index", url = "url-$index", type = null, poster = null)
                                    },
                                )), hasNext = page == 0))
                            }
                        }
                        visits++
                        SectionScreen("Source", 0, "Popular", "Popular",
                            onTitle = { _, _ -> nav.navigate("title") }, onBack = { nav.popBackStack() })
                    }
                    composable("title") {
                        Button(onClick = { nav.popBackStack() }) { Text("Return to collection") }
                    }
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(60)
        compose.onNodeWithText("Title 60").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Return to collection").performClick()
        compose.onNodeWithText("Title 60").assertIsDisplayed()
        compose.runOnIdle {
            assertSame(original, current)
            assertEquals(2, current.pager.pages.size)
            assertEquals(120, current.pager.rows.single().items.size)
        }
    }
}
