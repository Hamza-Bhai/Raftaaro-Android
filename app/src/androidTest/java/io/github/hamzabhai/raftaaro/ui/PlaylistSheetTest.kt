package io.github.hamzabhai.raftaaro.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hamzabhai.raftaaro.core.AudioFormat
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.PlaylistEntry
import io.github.hamzabhai.raftaaro.core.PlaylistInfo
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.ui.share.ShareSheetContent
import io.github.hamzabhai.raftaaro.ui.share.ShareState
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistSheetTest {
    @get:Rule val rule = createComposeRule()

    private val entries = listOf(PlaylistEntry("u1", "First", 61), PlaylistEntry("u2", "Second", null), PlaylistEntry("u3", "Third", 5))
    private var picked: Pair<List<PlaylistEntry>, VideoChoice>? = null

    private fun show() = rule.setContent {
        RaftaaroTheme {
            ShareSheetContent(
                ShareState.Playlist(PlaylistInfo("https://youtube.com/playlist?list=1", "My: list", entries)),
                onPick = {}, onPlaylist = { e, c -> picked = e to c }, onSignIn = {}, onClose = {}
            )
        }
    }

    @Test fun uncheckingOneOfThreeDownloadsTwo() {
        show()
        rule.onNodeWithText("3 videos").assertExists()
        rule.onNodeWithText("Second").performClick()
        rule.onNodeWithText("Download 2").performClick()
        assertEquals(listOf("u1", "u3"), picked!!.first.map { it.url })
        assertEquals(VideoChoice(MediaType.VIDEO, null), picked!!.second)
    }

    @Test fun selectAllTogglesEverything() {
        show()
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Download 0").assertExists()
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Download 3").assertExists()
    }

    @Test fun theQualityAppliesToAll() {
        show()
        rule.onNodeWithText("720p").performClick()
        rule.onNodeWithText("Download 3").performClick()
        assertEquals(VideoChoice(MediaType.VIDEO, 720), picked!!.second)
        rule.onNodeWithText("MP3").performClick()
        rule.onNodeWithText("Download 3").performClick()
        assertEquals(VideoChoice(MediaType.AUDIO, null, AudioFormat.MP3), picked!!.second)
    }
}
