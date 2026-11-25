package io.github.hamzabhai.raftaaro.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hamzabhai.raftaaro.core.Settings
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.ui.settings.SettingsContent
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()
    private var changed: Settings? = null
    private var signOut: Site? = null

    private fun show(s: Settings = Settings()) = rule.setContent {
        RaftaaroTheme {
            SettingsContent(
                settings = s, folderNames = emptyMap(), ytDlpVersion = "2026.11.23", updateMessage = null,
                signedIn = mapOf(Site.INSTAGRAM to true), appVersion = "0.1.0",
                onChange = { changed = it }, onPickFolder = {}, onResetFolder = {}, onUpdate = {},
                onSignIn = {}, onSignOut = { signOut = it }, onBack = {}
            )
        }
    }

    @Test fun movingTheParallelSliderToFiveStoresIt() {
        show()
        rule.onNodeWithTag("maxConcurrent").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        assertEquals(5, changed!!.maxConcurrent)
    }

    @Test fun showsTheDefaultFoldersAndYtDlpVersion() {
        show()
        rule.onNodeWithText("Download/Raftaaro/Videos").assertExists()
        rule.onNodeWithText("yt-dlp 2026.11.23", substring = true).performScrollTo().assertExists()
    }

    @Test fun aSignedInSiteOffersSignOut() {
        show()
        rule.onNodeWithText("Sign out").performScrollTo().performClick()
        assertEquals(Site.INSTAGRAM, signOut)
    }
}
