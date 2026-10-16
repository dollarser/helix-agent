package com.helix.app.chat

import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.storage.HelixStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Bounded synthetic load and raw timings; no speed threshold or extrapolation to a physical phone. */
class SessionProjectionLoadDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun largeDirectoryAndLongConversationRemainCompleteAcrossRefreshes() {
        val profile = InstrumentationRegistry.getArguments().getString("projectionLoad")
        assumeTrue("Explicit synthetic-owned-avd profile required", profile != null)
        require(profile == "synthetic-owned-avd")
        require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic load must use an owned emulator" }
        compose.resetDeterministicUiState()
        seed(compose.container().storage)
        val samples = Samples()
        repeat(5) { sample(it, samples) }
        val report =
            buildJsonObject {
                put("fixtureSessions", 600)
                put("fixtureMessages", 1000)
                put("ordinaryBodyBytes", 1024)
                put("directoryMs", JsonArray(samples.directory.map { JsonPrimitive(it) }))
                put("conversationMs", JsonArray(samples.conversation.map { JsonPrimitive(it) }))
                put("searchMsIncludingDebounce", JsonArray(samples.search.map { JsonPrimitive(it) }))
            }
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext.noBackupFilesDir
            .resolve("projection-load.json")
            .writeText(report.toString())
    }

    private fun seed(storage: HelixStorage) {
        require(storage.sessions.find(HEAVY) == null) { "Load fixture needs a fresh owned clone" }
        storage.withTransaction {
            repeat(600) { i -> storage.sessions.create("projection-load-$i", "Synthetic $i", null, null, i.toLong()) }
            repeat(1000) { i ->
                val body = if (i == 999) (0 until 5).joinToString(" ") { "load-needle-$it" } else "x".repeat(1024)
                storage.messages.append("projection-message-$i", HEAVY, null, "USER", "TEXT", body)
            }
        }
    }

    private fun sample(
        i: Int,
        samples: Samples,
    ) {
        samples.directory += measureDirectory(i)
        samples.conversation += measureConversation()
        samples.search += measureSearch(i)
    }

    private fun measureDirectory(i: Int): Long {
        val storage = compose.container().storage
        val chat = compose.container().chatService
        compose.navigateTo("conversation/history")
        val measuredTitle = "Measured $i"
        val directoryTarget = "projection-load-599"
        return timed {
            storage.sessions.updateDetails(
                directoryTarget,
                measuredTitle,
                storage.sessions.resolve(directoryTarget).directoryRef,
            )
            chat.refreshSessions()
            compose.waitUntil(30_000) {
                val rows = chat.sessions.value
                rows.count { it.id.startsWith("projection-load-") } == 600 &&
                    rows.single { it.id == directoryTarget }.title == measuredTitle
            }
            compose.waitForIdle()
        }
    }

    private fun measureConversation(): Long {
        val chat = compose.container().chatService
        // Leave history before changing the selected session; navigation observes that selection.
        compose.navigateTo("sessions")
        chat.closeSession()
        compose.waitUntil(30_000) { chat.screen.value.openSessionId == null }
        compose.waitForIdle()
        val elapsed =
            timed {
                chat.openSession(HEAVY)
                compose.waitUntil(30_000) {
                    chat.screen.value.openSessionId == HEAVY &&
                        chat.screen.value.messages.size == 100 && chat.screen.value.hasOlderMessages
                }
                compose.waitForIdle()
            }
        assertEquals(
            "projection-message-900",
            chat.screen.value.messages
                .first()
                .id,
        )
        assertEquals(
            "projection-message-999",
            chat.screen.value.messages
                .last()
                .id,
        )
        repeat(9) { page ->
            chat.loadOlderMessages()
            compose.waitUntil(30_000) { chat.screen.value.messages.size == (page + 2) * 100 }
        }
        assertEquals(
            "projection-message-0",
            chat.screen.value.messages
                .first()
                .id,
        )
        assertFalse(chat.screen.value.hasOlderMessages)
        chat.showLatestMessages()
        compose.waitUntil(30_000) { chat.screen.value.messages.size == 100 }
        assertTrue(chat.screen.value.hasOlderMessages)
        return elapsed
    }

    private fun measureSearch(i: Int): Long {
        val chat = compose.container().chatService
        val elapsed =
            timed {
                chat.searchSessions("load-needle-$i")
                compose.waitUntil(30_000) {
                    val state = chat.sessionSearch.value
                    state.query == "load-needle-$i" && state.hits.any { it.sessionId == HEAVY && it.matchesMessage }
                }
            }
        assertTrue(
            chat.sessionSearch.value.hits
                .isNotEmpty(),
        )
        assertFalse(
            chat.sessionSearch.value.hits
                .any { it.sessionId != HEAVY },
        )
        chat.clearSessionSearch()
        return elapsed
    }

    private class Samples {
        val directory = mutableListOf<Long>()
        val conversation = mutableListOf<Long>()
        val search = mutableListOf<Long>()
    }

    private companion object {
        const val HEAVY = "projection-load-0"
    }

    private fun timed(action: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        action()
        return SystemClock.elapsedRealtime() - start
    }
}
