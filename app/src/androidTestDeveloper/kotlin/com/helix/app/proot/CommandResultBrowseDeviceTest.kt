package com.helix.app.proot

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.ModelEvent
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * HXA-194 slice 3 — the DEVELOPER quadrant of the command details page: a bash call that
 * carries a prepared-job binding AND a locally persisted, integrity-verified result archive.
 * The page must show the binding identity, the archive's streams OVER the older persisted
 * content (the fixture deliberately stores a "stale" stdout that the archive replaces), the
 * archive's file list, and nothing it never has (acknowledgement state, execution surface).
 *
 * The fixture replays the production persistence path: the audit binding row, a manifest
 * verified with [com.helix.runtime.proot.core.ZipJobExtractor] through
 * [ProotResultStore.persist] into the app-scope workspace, and a SUCCEEDED [ProotJobRecord].
 * No Runtime process, no network, no acknowledgement — persist and browse are pure local
 * reads, which is exactly what the details page may do.
 */
@RunWith(AndroidJUnit4::class)
class CommandResultBrowseDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedBoundCommandFixture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = containerFromApp().storage
        if (storage.sessions.list().none { it.id == SESSION }) {
            storage.sessions.create(
                SESSION,
                "HXA194 developer command fixture",
                null,
                null,
                SystemClock().now().toEpochMilli(),
            )
        }
        if (storage.turns.find(TURN) == null) {
            seedTurn(storage)
        }
        if (storage.toolCalls.listByTurn(TURN).none { it.callId == CALL }) {
            // The production contract: the row's primary key id IS the call id (the
            // tool_results FK references tool_calls.id, not the call_id column).
            storage.toolCalls.append(
                CALL,
                TURN,
                CALL,
                "bash",
                "1",
                """{"command":["cat out.txt"]}""",
                "COMPLETED",
            )
            // The persisted content is OLDER than the archive: the page must prefer the
            // verified archive's streams over this stale stdout.
            storage.toolResults.append(
                "hxa194-dev-result",
                CALL,
                "SUCCEEDED",
                "Command succeeded",
                """{"state":"SUCCEEDED","exitCode":0,"stdout":"stale persisted output","stderr":""}""",
            )
            storage.auditEvents.append(
                id = "proot-job-$CALL",
                correlationId = SESSION,
                type = "proot.job_prepared",
                actor = "platform",
                redactedPayload =
                    """{"version":1,"toolCallId":"$CALL","turnId":"$TURN","jobId":"$JOB_ID",
                    "executionId":"$EXECUTION_ID","inputManifestSha256":"$INPUT_MANIFEST"}""",
                timestamp = SystemClock().now().toEpochMilli(),
            )
        }
        persistVerifiedArchive(storage, context)
    }

    private fun seedTurn(storage: HelixStorage) {
        TurnCoordinator
            .start(
                storage,
                SystemClock(),
                { UUID.randomUUID().toString() },
                TurnStartSpec(SESSION, TURN, "$TURN-model", "command-fixture", "HXA194 developer fixture"),
            ).apply {
                beginModelStream().apply(ModelEvent.TextDelta("HXA194 developer fixture result"))
                terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            }
    }

    /**
     * Replays the production persistence path once: the verified archive and its
     * SUCCEEDED record land in the app-scope workspace, exactly as a real job would.
     */
    private fun persistVerifiedArchive(
        storage: HelixStorage,
        context: Context,
    ) {
        val store =
            ProotResultStore(
                storage,
                File(context.filesDir, "workspaces/app"),
                File(context.cacheDir, "proot-results"),
            )
        if (store.readLocal(TURN, CALL) == null) {
            val (archive, manifestHash) = buildArchive(File(context.cacheDir, "hxa194-fixture"))
            val record =
                ProotJobRecord(
                    JOB_ID,
                    EXECUTION_ID,
                    INPUT_MANIFEST,
                    ProotJobState.SUCCEEDED,
                    1,
                    terminalAtEpochMs = 2,
                    exitCode = 0,
                    outputManifestSha256 = manifestHash,
                )
            archive.inputStream().use { store.persist(TURN, CALL, record, it) }
        }
    }

    /** The flavor seam itself: binding facts + verified archive, no read failure, no ack state. */
    @Test
    fun browseSeamReturnsTheBindingAndTheVerifiedArchive() {
        val facts = ProotToolModule.browseCommandResult(containerFromApp().storage, TURN, CALL)
        assertFalse(facts.archiveReadFailed)
        val binding = requireNotNull(facts.binding) { "the prepared-job binding must resolve" }
        assertEquals(JOB_ID, binding.jobId)
        assertEquals(EXECUTION_ID, binding.executionId)
        assertEquals(INPUT_MANIFEST, binding.inputManifestSha256)
        val archive = requireNotNull(facts.archive) { "the persisted archive must be readable" }
        assertEquals(ARCHIVE_STDOUT, archive.stdout)
        assertEquals(ARCHIVE_STDERR, archive.stderr)
        assertFalse(archive.truncated)
        assertEquals(listOf("out.txt"), archive.files.map { it.path })
        assertNull("browse must not surface or set acknowledgement state", archive.acknowledged)
    }

    /** The page shows the archive over the persisted content, long streams, files and binding. */
    @Test
    fun detailPageShowsTheVerifiedArchiveOverThePersistedContent() {
        compose.resetDeterministicUiState()
        compose.navigateTo("tasks")
        openCommandDetailFromTasks()
        // The page reads its facts asynchronously; the state tag only exists once that
        // read has resolved, so wait for it before asserting the projection.
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("command-detail-state-succeeded").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("command-detail-state-succeeded").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-command").assertTextContains("cat out.txt")
        // Archive precedence: the stale persisted stdout must NOT be the displayed stream.
        // Compose ui-test 1.11.4's default assertTextContains matches the FULL text value
        // (substring = false), so assert the entire 48-line stream: both ends present and
        // nothing dropped proves the long content laid out into the scrolling column.
        compose.onNodeWithTag("command-detail-stdout").assertTextContains(ARCHIVE_STDOUT)
        compose.onNodeWithTag("command-detail-stderr").assertTextContains(ARCHIVE_STDERR)
        compose.onNodeWithTag("command-detail-exit").assertIsDisplayed()
        // The archive's file row and the binding lines sit BELOW the long streams in the
        // scrolling column, so each must be scrolled into view before a display assert —
        // that is the acceptance list's scrollability clause. The file row is tagged per
        // path because the command line also contains "out.txt" and the row text carries
        // size/hash suffixes.
        compose.onNodeWithTag("command-detail-file-out.txt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(JOB_ID, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(EXECUTION_ID, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(INPUT_MANIFEST, substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(
            "streams are present, so no no-output line",
            compose.onAllNodesWithTag("command-detail-no-output").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("command-detail-back").performClick()
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tasks-turn-$TURN").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Rebuilding the activity (rotation equivalent) re-reads the same verified facts. */
    @Test
    fun reopeningTheDetailPageAfterRecreationShowsTheSameVerifiedFacts() {
        val storage = containerFromApp().storage
        compose.resetDeterministicUiState()
        assertEquals(
            "the fixture publishes exactly one archive artifact",
            1,
            storage.artifacts.listBySession(SESSION).size,
        )
        compose.navigateTo("tasks")
        openCommandDetailFromTasks()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("command-detail-state-succeeded").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("command-detail-stdout").assertTextContains(ARCHIVE_STDOUT)
        compose.runOnUiThread { compose.activity.recreate() }
        compose.waitForIdle()
        // The rebuilt page re-reads its facts asynchronously; the state tag only exists
        // once that read has resolved, so wait for it before asserting the projection.
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("command-detail-state-succeeded").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("command-detail-state-succeeded").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-stdout").assertTextContains(ARCHIVE_STDOUT)
        assertEquals(
            "re-reading must not republish the archive",
            1,
            storage.artifacts.listBySession(SESSION).size,
        )
        compose.onNodeWithTag("command-detail-back").performClick()
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tasks-turn-$TURN").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------- helpers ----------

    /** A manifest-verified job archive: two streams + one file, exactly as the preview reads them. */
    private fun buildArchive(root: File): Pair<File, String> {
        check(root.mkdirs() || root.isDirectory)
        val stdout = File(root, "stdout.txt").apply { writeText(ARCHIVE_STDOUT) }
        val stderr = File(root, "stderr.txt").apply { writeText(ARCHIVE_STDERR) }
        val out = File(root, "out.txt").apply { writeText("HXA194 fixture file content") }
        val manifest =
            JobManifestCodec.encode(
                JobManifest(
                    // The codec's canonical encoding requires entries sorted by path.
                    listOf(
                        JobManifestEntry("out.txt", FileContentStore.sha256Hex(out), out.length()),
                        JobManifestEntry("stderr.txt", FileContentStore.sha256Hex(stderr), stderr.length()),
                        JobManifestEntry("stdout.txt", FileContentStore.sha256Hex(stdout), stdout.length()),
                    ),
                ),
            )
        val archive = File(root, "hxa194-fixture.zip")
        JobZipWriter(archive.outputStream()).use {
            it.writeManifest(manifest)
            it.writeEntry("out.txt", out)
            it.writeEntry("stderr.txt", stderr)
            it.writeEntry("stdout.txt", stdout)
        }
        return archive to FileContentStore.sha256Hex(manifest.toByteArray())
    }

    private fun openCommandDetailFromTasks() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tasks-turn-$TURN").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("tasks-turn-command-$TURN").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("turn-command-detail-$CALL").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("turn-command-detail-$CALL").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("screen-command-detail").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun containerFromApp(): AppContainer =
        (ApplicationProvider.getApplicationContext<Context>() as HelixApplication).appContainer

    companion object {
        private const val SESSION = "hxa194-dev-session"
        private const val TURN = "hxa194-dev-turn"
        private const val CALL = "hxa194-dev-call"
        private const val JOB_ID = "job_194a00000001"
        private const val EXECUTION_ID = "execution-194"

        // Long multi-line streams: the details page must lay out a realistic archive
        // volume in its scrolling column (the acceptance list's layout clause).
        val ARCHIVE_STDOUT: String =
            (1..48).joinToString("\n") { "archived stdout line $it from the verified job" }
        val ARCHIVE_STDERR: String = "archived stderr from the verified job".repeat(24)
        private val INPUT_MANIFEST = "b".repeat(64)
    }
}
