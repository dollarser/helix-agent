package com.helix.app

import android.net.Uri
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.files.ConflictPolicy
import com.helix.app.files.ExportTarget
import com.helix.app.files.TransferItemStatus
import com.helix.app.test.TransferTestDocumentsProvider
import com.helix.app.ui.ASYNC_UI_TIMEOUT_MILLIS
import com.helix.app.ui.ArtifactExportState
import com.helix.app.ui.ArtifactExternalOpenResult
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.openArtifactExternal
import com.helix.app.ui.resetDeterministicUiState
import com.helix.app.ui.startArtifactExport
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.SafCancelToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * HXA-203 — the artifact delivery loop on a real device, run by BOTH flavor test variants:
 * the file dialog re-checks the REAL file at open and shows four different honest states
 * (missing file, revoked scope grant, content replaced, preview truncated — never a silent
 * re-read or a claimed result), the same file name in two scopes stays two rows with two
 * contents, the export path publishes ONLY the pipeline's own outcome (a cancelled run reads
 * as cancelled, never "exported"), and the no-viewer external open is decided by a real
 * resolveActivity pre-check. The batch-A exit journey walks 会话 → 任务 → 命令详情 / 产物 →
 * 来源 with the 202/194 stable identities, reading the turn's files by ownership.
 *
 * Boundary (recorded honestly): the system "create document" picker and the OS chooser are
 * not driven from an instrumented test — the export and external-open actions are exercised
 * at the pipeline level (the dialog's own [startArtifactExport] and [openArtifactExternal]
 * with a real content:// sink) instead of clicking the OS dialog; the buttons' presence and
 * gating ARE asserted in the UI.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("TooManyFunctions") // One device-acceptance class per the HXA-203 scenario list.
class ArtifactDeliveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun seedDeliveryFixture() {
        runBlocking {
            val storage = compose.container().storage
            if (storage.sessions.list().none { it.id == SESSION }) {
                storage.sessions.create(SESSION, "HXA203 delivery fixture", null, null, 1000)
            }
            if (storage.turns.find(TURN) == null) {
                storage.withTransaction {
                    storage.turns.start(id = TURN, sessionId = SESSION, startedAt = 1000)
                    var turn = storage.turns.resolve(TURN)
                    turn = storage.turns.updateState(turn, TurnState.BUILDING_CONTEXT, 0, null, null)
                    turn = storage.turns.updateState(turn, TurnState.WAITING_MODEL, 0, null, null)
                    turn = storage.turns.updateState(turn, TurnState.RECEIVING_MODEL, 0, null, null)
                    storage.turns.updateState(turn, TurnState.COMPLETED, 0, 2000, null)
                }
            }
            // The 194 stable call identity (idempotent with CommandExecutionDetailsDeviceTest):
            // the journey's command-details leg reuses the exact settled call.
            if (storage.toolCalls.listByTurn(TURN).none { it.callId == CALL_SUCCEEDED }) {
                storage.toolCalls.append(
                    CALL_SUCCEEDED,
                    TURN,
                    CALL_SUCCEEDED,
                    "bash",
                    "1",
                    """{"command":["echo hello"]}""",
                    "COMPLETED",
                )
                storage.toolResults.append(
                    "hxa194-result-$CALL_SUCCEEDED",
                    CALL_SUCCEEDED,
                    "SUCCEEDED",
                    "Command succeeded",
                    """{"state":"SUCCEEDED","exitCode":0,"stdout":"hello from fixture","stderr":""}""",
                )
            }
            storage.messages.append(
                "hxa203-result-${UUID.randomUUID()}",
                SESSION,
                TURN,
                "assistant",
                "text",
                "HXA203 fixture result: the report is complete.",
            )
        }
    }

    /** Same file name, two scopes: the rows keep their OWN scope's content and delivery surface. */
    @Test
    fun sameNameInTwoScopesStaysTwoRowsWithTheirOwnContents() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val wsRow = seedWorkspaceArtifact("output/note.txt", "HXA203 workspace note body\n".toByteArray())
        val (scopeId, safRow) = seedSafTreeArtifact("note.txt", "v1".toByteArray())
        try {
            openBothRows(wsRow.id, safRow.id)
            compose
                .onNodeWithTag("artifact-file-name-${wsRow.id}", useUnmergedTree = true)
                .assertTextEquals("note.txt")
            compose
                .onNodeWithTag("artifact-file-name-${safRow.id}", useUnmergedTree = true)
                .assertTextEquals("note.txt")
            // The workspace row: its own content, and the in-app export is offered.
            compose.onNodeWithTag("artifact-file-row-${wsRow.id}").performClick()
            compose.waitForIdle()
            waitDialogTag("artifact-file-preview")
            compose
                .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
                .assertTextContains("HXA203 workspace note body", substring = true)
            compose
                .onNodeWithTag("artifact-file-export-${wsRow.id}", useUnmergedTree = true)
                .assertIsDisplayed()
            closeFileView(wsRow.id)
            // The SAF row: the tree document's content, NO in-app export (scope crossing is
            // refused), and open-external offers the staged copy instead.
            compose.onNodeWithTag("artifact-file-row-${safRow.id}").performClick()
            compose.waitForIdle()
            waitDialogTag("artifact-file-preview")
            compose
                .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
                .assertTextContains("v1", substring = true)
            assertTrue(
                "a SAF-scope row must not offer the in-app export",
                compose
                    .onAllNodesWithTag("artifact-file-export-${safRow.id}", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            compose
                .onNodeWithTag("artifact-file-open-external-${safRow.id}", useUnmergedTree = true)
                .assertIsDisplayed()
            closeFileView(safRow.id)
        } finally {
            container.safTree.revoke(scopeId)
        }
    }

    /** 旧数据: the row outlives the file — Ready at first open, the honest missing state after. */
    @Test
    fun deletedFileShowsTheHonestMissingState() {
        compose.resetDeterministicUiState()
        val row = seedWorkspaceArtifact("output/stale.txt", "HXA203 stale body\n".toByteArray())
        val file = workspaceFile("output/stale.txt")
        openFileViewFromArtifactsPage(row.id)
        waitDialogTag("artifact-file-preview")
        compose
            .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
            .assertTextContains("HXA203 stale body", substring = true)
        closeFileView(row.id)
        check(file.delete())
        openFileViewFromArtifactsPage(row.id)
        waitDialogTag("artifact-file-missing")
        compose.onNodeWithTag("artifact-file-missing", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithTag("artifact-file-share-${row.id}", useUnmergedTree = true)
            .assertIsNotEnabled()
        closeFileView(row.id)
    }

    /** 替换: a positive size/hash mismatch against the task's recorded facts shows the banner. */
    @Test
    fun replacedFileShowsTheChangedBannerWithTheNewContent() {
        compose.resetDeterministicUiState()
        val row = seedWorkspaceArtifact("output/replaced.txt", "HXA203 original content\n".toByteArray())
        val file = workspaceFile("output/replaced.txt")
        openFileViewFromArtifactsPage(row.id)
        waitDialogTag("artifact-file-preview")
        assertTrue(
            "an unchanged file shows no changed banner",
            compose
                .onAllNodesWithTag("artifact-file-changed", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        closeFileView(row.id)
        file.writeBytes("HXA203 REPLACED — bigger content, different size\n".toByteArray())
        openFileViewFromArtifactsPage(row.id)
        waitDialogTag("artifact-file-changed")
        compose.onNodeWithTag("artifact-file-changed", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
            .assertTextContains("HXA203 REPLACED", substring = true)
        closeFileView(row.id)
    }

    /** 撤权: a scope id with no grant in the app's store fails closed as REVOKED, not missing. */
    @Test
    fun unresolvableSafScopeShowsTheRevokedState() {
        compose.resetDeterministicUiState()
        val row = seedUnresolvableSafArtifact()
        openFileViewFromArtifactsPage(row.id)
        waitDialogTag("artifact-file-revoked")
        compose.onNodeWithTag("artifact-file-revoked", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "a revoked scope never shows a preview",
            compose
                .onAllNodesWithTag("artifact-file-preview", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        closeFileView(row.id)
    }

    /** Unicode: a CJK + emoji file name and body survive the row, the dialog and the preview. */
    @Test
    fun unicodeNameAndContentSurviveTheJourney() {
        compose.resetDeterministicUiState()
        val name = "产物报告-🚀-${UUID.randomUUID()}.txt"
        val row = seedWorkspaceArtifact("output/$name", "中文正文 HXA203 🎉\n".toByteArray())
        openFileViewFromArtifactsPage(row.id)
        compose
            .onNodeWithTag("artifact-file-name-${row.id}", useUnmergedTree = true)
            .assertTextEquals(name)
        waitDialogTag("artifact-file-preview")
        compose
            .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
            .assertTextContains("中文正文 HXA203 🎉", substring = true)
        closeFileView(row.id)
    }

    /** 大文件: a text file past the 64 KiB preview cap previews its head AND says so. */
    @Test
    fun largeTextFileShowsTheTruncationMarker() {
        compose.resetDeterministicUiState()
        val body = "HXA203 large head\n" + "x".repeat(200 * 1024)
        val row = seedWorkspaceArtifact("output/large.txt", body.toByteArray())
        openFileViewFromArtifactsPage(row.id)
        waitDialogTag("artifact-file-preview-truncated")
        compose
            .onNodeWithTag("artifact-file-preview-truncated", useUnmergedTree = true)
            .assertIsDisplayed()
        compose
            .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
            .assertTextContains("HXA203 large head", substring = true)
        closeFileView(row.id)
    }

    /**
     * 取消导出: the pipeline itself reports CANCELLED for a pre-cancelled token, and the
     * dialog's own export path (startArtifactExport, the SAME call the picker feeds) honors a
     * mid-run cancellation — the flag flips while the pipeline copies (cancel is polled per
     * chunk), and the published state is the pipeline's CANCELLED, never "exported". No OS
     * picker is involved at this level.
     */
    @Test
    fun cancelledExportPublishesTheCancelledOutcomeNeverExported() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val file = workspaceFile("output/cancel-src.txt")
        // (a) The pipeline alone: a pre-cancelled token reads CANCELLED from its own result.
        file.writeBytes("HXA203 cancel source\n".toByteArray())
        val pipeline =
            container.fileManager.exportDocument(
                "output/cancel-src.txt",
                ExportTarget.Document(docUri("sink"), "cancel-${UUID.randomUUID()}.txt"),
                ConflictPolicy.ASK,
                SafCancelToken { true },
            ) { _, _ -> }
        assertEquals(TransferItemStatus.CANCELLED, pipeline.items.first().status)
        // (b) The dialog's export function: startArtifactExport resets the flag on entry by
        // contract, so the cancellation is a MID-RUN action — start the run, flip the flag
        // immediately (the launched copy has not reached its first chunk poll yet), with a
        // 16 MiB source so the copy window stays open; the published state must be the
        // pipeline's own CANCELLED, never Completed.
        file.writeBytes(ByteArray(16 * 1024 * 1024) { (it % 251).toByte() })
        val row =
            ArtifactRowUi(
                id = "hxa203-cancel-row",
                sessionId = SESSION,
                relativePath = FileScopePath(APP_SCOPE, "output/cancel-src.txt").toModelReference(),
                fileName = "cancel-src.txt",
                mediaType = "text/plain",
                sizeBytes = file.length(),
                sha256 = sha256Hex(file.readBytes()),
                turnId = null,
                sessionTitle = null,
                isSafScope = false,
            )
        val cancelFlag = mutableStateOf(false)
        val exportState = mutableStateOf<ArtifactExportState>(ArtifactExportState.Idle)
        startArtifactExport(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            container.fileManager,
            row,
            cancelFlag,
            exportState,
            Uri.parse(docUri("sink")),
        )
        cancelFlag.value = true
        stopAwait { exportState.value is ArtifactExportState.Cancelled }
    }

    /**
     * 外部查看器不存在: a type no installed app handles is reported as NO VIEWER by the
     * resolveActivity pre-check (nothing is launched, nothing is claimed); the real probed
     * type of the same file must at least succeed staging + provider + launch (not fail).
     */
    @Test
    fun externalOpenWithoutAViewerReportsNoViewerAndNeverLaunches() {
        compose.resetDeterministicUiState()
        val container = compose.container()
        seedWorkspaceArtifact("output/ext-viewer.txt", "HXA203 external body\n".toByteArray())
        val context = compose.activity
        assertEquals(
            ArtifactExternalOpenResult.NoViewer,
            openArtifactExternal(
                context,
                container.fileManager,
                APP_SCOPE,
                "output/ext-viewer.txt",
                "application/x-helix-never-registered",
            ),
        )
        val real =
            openArtifactExternal(context, container.fileManager, APP_SCOPE, "output/ext-viewer.txt", null)
        assertTrue(
            "staging + provider + launch must not fail for a real file (got $real)",
            real != ArtifactExternalOpenResult.Failed,
        )
        if (real == ArtifactExternalOpenResult.Launched) {
            // A chooser is now on top of the test activity: send a back key and let it settle.
            Thread.sleep(800)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
        }
    }

    /**
     * The batch-A exit path with the 202/194 stable identities: 会话 (the owning session
     * opens) → 任务 (the dashboard's settled turn) → 命令详情 (the 194 settled call; 返回 comes
     * back) and 产物 (the turn's OWN file, read by ownership) → 来源 (the global page's file
     * dialog 打开任务 lands on the turn route whose 返回 goes back to the page the row was
     * opened from). The durable facts are asserted unchanged at the end.
     */
    @Suppress("LongMethod") // One linear walkthrough: session, task, command, artifact, source.
    @Test
    fun batchExitJourneySessionTaskCommandAndArtifactBackToSource() {
        val container = compose.container()
        val storage = container.storage
        val row =
            seedWorkspaceArtifact(
                "output/journey-${UUID.randomUUID()}.txt",
                "HXA203 journey artifact\n".toByteArray(),
            )
        compose.resetDeterministicUiState()
        val before = facts(storage)
        // 会话: the owning session opens with the 194 identity (service cache reloaded first).
        container.chatService.refreshSessions()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("chat-session-$SESSION").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("chat-session-$SESSION").performScrollTo().performClick()
        compose.waitForIdle()
        stopAwait { container.chatService.screen.value.openSessionId == SESSION }
        // 任务 + 命令详情 (194): the settled call opens its details page; 返回 returns to the list.
        compose.navigateTo("tasks")
        waitTurnRowVisible()
        openCommandDetail()
        compose.onNodeWithTag("command-detail-state-succeeded").assertIsDisplayed()
        compose.onNodeWithTag("command-detail-back").performClick()
        compose.waitForIdle()
        waitTurnRowVisible()
        // 产物: the turn's OWN file by ownership (never the truncated global window).
        compose.onNodeWithTag("tasks-turn-artifacts-$TURN").performScrollTo().performClick()
        compose.waitForIdle()
        waitDialogTag("artifact-file-row-${row.id}")
        compose.onNodeWithTag("artifact-file-row-${row.id}").performClick()
        compose.waitForIdle()
        waitDialogTag("artifact-file-preview")
        compose
            .onNodeWithTag("artifact-file-preview", useUnmergedTree = true)
            .assertTextContains("HXA203 journey artifact", substring = true)
        assertTrue(
            "the task-ownership context offers no redundant open-task link",
            compose
                .onAllNodesWithTag("artifact-file-open-task-${row.id}", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        closeFileView(row.id)
        compose.onNodeWithTag("tasks-artifacts-close", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        waitTurnRowVisible()
        // 来源: from the global page the file dialog's 打开任务 lands on the turn route; 返回
        // pops back to the page the row was opened from.
        openFileViewFromArtifactsPage(row.id)
        compose
            .onNodeWithTag("artifact-file-open-task-${row.id}", useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()
        // The landing IS the turn's result dialog (rendered in place of the dashboard list,
        // no back bar while the dialog is open): its turn-specific collect button proves the
        // route opened the RIGHT turn. System back dismisses the dialog; the route's own back
        // bar then appears on the dashboard.
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose
                .onAllNodesWithTag("task-collect-$TURN", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        Thread.sleep(300)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tasks-turn-back-bar").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("tasks-turn-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screen-artifacts").assertExists()
        assertEquals("the journey must start or record nothing", before, facts(storage))
    }

    // ---------- seeding ----------

    /** The REAL workspace file + its verified row; returns the stored row (its id may be a
     *  pre-existing row's id when the (session, path) key already has one). */
    private fun seedWorkspaceArtifact(
        relativePath: String,
        bytes: ByteArray,
    ): ArtifactEntity {
        val container = compose.container()
        val storedRef = FileScopePath(APP_SCOPE, relativePath).toModelReference()
        val file = workspaceFile(relativePath).apply { writeBytes(bytes) }
        var entity: ArtifactEntity? = null
        runBlocking {
            container.storage.withTransaction {
                entity =
                    container.storage.artifacts.registerOrRefresh(
                        "hxa203-${UUID.randomUUID()}",
                        SESSION,
                        storedRef,
                        "text/plain",
                        bytes.size.toLong(),
                        sha256Hex(bytes),
                        TURN,
                        file,
                    )
            }
        }
        return requireNotNull(entity)
    }

    /** Grants the transfer-fixture tree and seeds a row for one of its REAL tree documents. */
    private fun seedSafTreeArtifact(
        artifactPath: String,
        backing: ByteArray,
    ): Pair<String, ArtifactEntity> {
        val container = compose.container()
        val scopeId =
            container.safTree.grant(TransferTestDocumentsProvider.treeUri(), "HXA203 fixture tree").scopeId
        val storedRef = FileScopePath(scopeId, artifactPath).toModelReference()
        val file =
            File(compose.activity.filesDir, "hxa203-saf-backing/$artifactPath").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(backing)
            }
        var entity: ArtifactEntity? = null
        runBlocking {
            container.storage.withTransaction {
                entity =
                    container.storage.artifacts.registerOrRefresh(
                        "hxa203-${UUID.randomUUID()}",
                        SESSION,
                        storedRef,
                        "text/plain",
                        backing.size.toLong(),
                        sha256Hex(backing),
                        TURN,
                        file,
                    )
            }
        }
        return scopeId to requireNotNull(entity)
    }

    /** A row whose scope id has NO grant in the app's store: resolution fails closed at open. */
    private fun seedUnresolvableSafArtifact(): ArtifactEntity {
        val container = compose.container()
        val storedRef = FileScopePath("saf-9f3c2ab7d401", "output/revoked.txt").toModelReference()
        val file =
            File(compose.activity.filesDir, "hxa203-revoked-backing/revoked.txt").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes("HXA203 revoked body\n".toByteArray())
            }
        var entity: ArtifactEntity? = null
        runBlocking {
            container.storage.withTransaction {
                entity =
                    container.storage.artifacts.registerOrRefresh(
                        "hxa203-${UUID.randomUUID()}",
                        SESSION,
                        storedRef,
                        "text/plain",
                        file.length(),
                        sha256Hex(file.readBytes()),
                        TURN,
                        file,
                    )
            }
        }
        return requireNotNull(entity)
    }

    private fun workspaceFile(relativePath: String): File =
        File(compose.activity.filesDir, "workspaces/$APP_SCOPE/$relativePath").apply {
            requireNotNull(parentFile).mkdirs()
        }

    // ---------- UI helpers ----------

    private fun openFileViewFromArtifactsPage(rowId: String) {
        compose.navigateTo("artifacts")
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("artifact-file-row-$rowId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artifact-file-row-$rowId").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun openBothRows(
        firstId: String,
        secondId: String,
    ) {
        compose.navigateTo("artifacts")
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            val first = compose.onAllNodesWithTag("artifact-file-row-$firstId").fetchSemanticsNodes()
            val second = compose.onAllNodesWithTag("artifact-file-row-$secondId").fetchSemanticsNodes()
            first.isNotEmpty() && second.isNotEmpty()
        }
    }

    /** Dialog-internal leaf tags come from the unmerged tree (the AlertDialog is a second window). */
    private fun waitDialogTag(tag: String) {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closeFileView(rowId: String) {
        compose.onNodeWithTag("artifact-file-close-$rowId", useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun waitTurnRowVisible() {
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("tasks-turn-$TURN").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openCommandDetail() {
        compose.onNodeWithTag("tasks-turn-command-$TURN").performScrollTo().performClick()
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose
                .onAllNodesWithTag("turn-command-detail-$CALL_SUCCEEDED")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithTag("turn-command-detail-$CALL_SUCCEEDED")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.waitUntil(ASYNC_UI_TIMEOUT_MILLIS) {
            compose.onAllNodesWithTag("screen-command-detail").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------- fact checks + utilities ----------

    /** The durable rows a delivery read must never grow: turns, tool calls, audit events. */
    private data class Facts(
        val turns: Int,
        val calls: Int,
        val audits: Int,
    )

    private fun facts(storage: HelixStorage): Facts =
        Facts(
            storage.turns.listBySession(SESSION).size,
            storage.toolCalls.listByTurn(TURN).size,
            storage.auditEvents.listByCorrelation(SESSION).size,
        )

    /** Polls [condition] every 25 ms for up to 30 s. */
    private fun stopAwait(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(25)
        assertTrue("production state must settle", condition())
    }

    private fun docUri(id: String): String = "content://${TransferTestDocumentsProvider.authority()}/document/$id"

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val SESSION = "hxa194-cmd-session"
        private const val TURN = "hxa194-cmd-turn"
        private const val CALL_SUCCEEDED = "hxa194-cmd-succeeded"
        private const val APP_SCOPE = "app"
    }
}
