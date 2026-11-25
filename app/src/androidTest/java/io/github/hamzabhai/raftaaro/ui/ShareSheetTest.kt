package io.github.hamzabhai.raftaaro.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hamzabhai.raftaaro.core.AudioFormat
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.QualityOption
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.VideoInfo
import io.github.hamzabhai.raftaaro.ui.share.ShareSheetContent
import io.github.hamzabhai.raftaaro.ui.share.ShareState
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareSheetTest {
    @get:Rule val rule = createComposeRule()

    private val info = VideoInfo("https://youtu.be/x", "Cats", 125, "CatChannel", null, emptyList())
    private val video = ShareState.Video(
        info,
        listOf(QualityOption(null, "⭐ Best (1080p)", 110_000_000), QualityOption(1080, "1080p", 110_000_000), QualityOption(720, "720p", null)),
        listOf(QualityOption(null, "MP3", 10_000_000, AudioFormat.MP3), QualityOption(null, "M4A", 10_000_000, AudioFormat.M4A))
    )

    private fun show(state: ShareState, onPick: (VideoChoice?) -> Unit = {}, onSignIn: (Site) -> Unit = {}) = rule.setContent {
        RaftaaroTheme { ShareSheetContent(state, onPick = onPick, onPlaylist = { _, _ -> }, onSignIn = onSignIn, onClose = {}) }
    }

    @Test fun videoStateListsBestFirstAndAudioAfterVideo() {
        show(video)
        val rows = rule.onAllNodesWithTag("quality").fetchSemanticsNodes().map { n ->
            n.config.first { it.key.name == "Text" }.value.toString()
        }
        assertEquals(true, rows.first().contains("⭐ Best (1080p)"))
        assertEquals(true, rows.indexOfFirst { it.contains("MP3") } > rows.indexOfFirst { it.contains("720p") })
        rule.onNodeWithText("CatChannel · 2:05").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText("~104.9 MB", substring = true).fetchSemanticsNodes().size)
    }

    @Test fun noLinkShowsTheMessage() {
        show(ShareState.NoLink("hello"))
        rule.onNodeWithText("No link found in what you shared.").assertIsDisplayed()
    }

    @Test fun tappingARowPicksThatChoice() {
        var picked: VideoChoice? = null
        show(video, onPick = { picked = it })
        rule.onNodeWithText("720p").performClick()
        assertEquals(VideoChoice(MediaType.VIDEO, 720), picked)
        rule.onNodeWithText("MP3").performScrollTo().performClick()
        assertEquals(VideoChoice(MediaType.AUDIO, null, AudioFormat.MP3), picked)
    }

    @Test fun aSignInFailureOffersTheSite() {
        var site: Site? = null
        show(ShareState.Failed("Sign-in required", Site.INSTAGRAM), onSignIn = { site = it })
        rule.onNodeWithText("Sign in to Instagram").performClick()
        assertEquals(Site.INSTAGRAM, site)
    }

    @Test fun aFileShowsNameSizeAndDownload() {
        var picked = false
        show(ShareState.File("https://x.com/a.zip", "a.zip", 1536), onPick = { picked = it == null })
        rule.onNodeWithText("a.zip").assertIsDisplayed()
        rule.onNodeWithText("1.5 KB").assertIsDisplayed()
        rule.onNodeWithText("Download").performClick()
        assertEquals(true, picked)
    }
}
