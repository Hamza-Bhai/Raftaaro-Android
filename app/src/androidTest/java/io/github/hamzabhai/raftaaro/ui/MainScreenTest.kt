package io.github.hamzabhai.raftaaro.ui

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.JobStatus
import io.github.hamzabhai.raftaaro.ui.main.CardAction
import io.github.hamzabhai.raftaaro.ui.main.MainScreenContent
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun job(id: String, status: JobStatus, error: String? = null) =
        Job(id = id, kind = JobKind.FILE, url = "https://x.com/$id.zip", title = "Title $id", status = status, error = error,
            totalBytes = 2048, receivedBytes = 1024)

    private val jobs = listOf(
        job("p", JobStatus.PAUSED),
        job("e", JobStatus.ERROR, "HTTP 404"),
        job("d", JobStatus.DONE)
    )
    private var action: Pair<String, CardAction>? = null

    private fun show(list: List<Job> = jobs) = rule.setContent {
        RaftaaroTheme {
            MainScreenContent(
                jobs = list, link = "", onLinkChange = {}, onPaste = {}, onDownload = {},
                onAction = { j, a -> action = j.id to a }, onSettings = {}
            )
        }
    }

    @Test fun aPausedCardOffersResume() {
        show()
        rule.onNodeWithText("Resume").performClick()
        assertEquals("p" to CardAction.RESUME, action)
    }

    @Test fun anErrorCardShowsItsMessageAndRetry() {
        show()
        rule.onNodeWithText("HTTP 404").assertExists()
        rule.onNodeWithText("Retry").performClick()
        assertEquals("e" to CardAction.RETRY, action)
    }

    @Test fun oneListShowsEveryJobWithFinishedOnesBelow() {
        show(listOf(job("d", JobStatus.DONE), job("p", JobStatus.PAUSED), job("e", JobStatus.ERROR, "HTTP 404")))
        rule.onNodeWithText("Active").assertDoesNotExist()
        rule.onNodeWithText("Finished").assertDoesNotExist()
        val top = listOf("p", "e", "d").map { rule.onNodeWithText("Title $it").getUnclippedBoundsInRoot().top.value }
        assertTrue("unfinished jobs sit above finished ones: $top", top[0] < top[2] && top[1] < top[2])
        rule.onNodeWithText("1 paused · 1 failed · 1 done").assertExists()
    }

    @Test fun aFinishedCardOffersOpenAndShare() {
        show()
        rule.onNodeWithText("Share").performClick()
        assertEquals("d" to CardAction.SHARE, action)
        rule.onNodeWithText("Open").performClick()
        assertEquals("d" to CardAction.OPEN, action)
    }

    @Test fun anEmptyListExplainsWhatToDo() {
        show(emptyList())
        rule.onNodeWithText("Share a video to Raftaaro from any app, or paste a link above.").assertExists()
    }
}
