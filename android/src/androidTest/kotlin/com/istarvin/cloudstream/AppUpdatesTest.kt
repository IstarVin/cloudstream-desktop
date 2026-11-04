package com.istarvin.cloudstream

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.istarvin.cloudstream.ui.AppUpdateContent
import com.istarvin.cloudstream.update.AndroidRelease
import com.istarvin.cloudstream.update.UpdateState
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import com.istarvin.cloudstream.ui.AppTheme
import com.istarvin.cloudstream.ui.SettingsScreen
import org.junit.Rule
import org.junit.Test

class AppUpdatesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun currentVersionOffersAnotherCheck() {
        var checks = 0
        compose.setContent {
            AppTheme { Surface(Modifier.fillMaxSize()) {
                AppUpdateContent("0.1", UpdateState.Current, { checks++ }, {}, {}, {})
            } }
        }
        compose.onNodeWithText("You're up to date.").assertExists()
        compose.onNodeWithText("Check for updates").performClick()
        assertEquals(1, checks)
        captureTvScreen("app-updates-current")
    }

    @Test fun downloadingOffersCancellation() {
        var cancellations = 0
        compose.setContent {
            AppTheme { Surface(Modifier.fillMaxSize()) {
                AppUpdateContent("0.1", UpdateState.Downloading(AndroidRelease("android-v0.2.0"), 1048576, 4194304),
                    {}, {}, {}, { cancellations++ })
            } }
        }
        compose.onNodeWithText("1.0 MB of 4.0 MB").assertExists()
        compose.onNodeWithText("Check for updates").assertDoesNotExist()
        captureTvScreen("app-updates-downloading")
        compose.onNodeWithText("Cancel download").performClick()
        assertEquals(1, cancellations)
    }

    @Test fun failedDownloadOffersRetry() {
        var retries = 0
        compose.setContent {
            AppTheme { Surface(Modifier.fillMaxSize()) {
                AppUpdateContent("0.1", UpdateState.Failed("Download failed. Try again.", AndroidRelease("android-v0.2.0")),
                    {}, { retries++ }, {}, {})
            } }
        }
        compose.onNodeWithText("Download failed. Try again.").assertExists()
        compose.onNodeWithText("Retry download").performClick()
        assertEquals(1, retries)
        captureTvScreen("app-updates-failed")
    }

    @Test fun updatesAreAvailableWithoutAReachableDesktop() {
        compose.setContent { AppTheme { SettingsScreen(onUnpair = {}) } }
        compose.onNodeWithTag("settings-categories").performScrollToNode(hasText("App updates"))
        compose.onNodeWithText("App updates").performClick()
        compose.onNodeWithText("Installed version:", substring = true).assertExists()
        compose.onNodeWithText("Check for updates").assertIsEnabled()
        captureTvScreen("app-updates")
    }
}
