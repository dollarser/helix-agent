package com.helix.app.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HXA-046 文件管理 UI, verified on a real device (the consumer gate runs this; the developer build
 * also passes it — the extra all-files source is simply one more chip the test ignores). It drives
 * the same [com.helix.app.files.FileManagerService] facade the model's `files.*` tools use, so the
 * containment / no-default-overwrite / trash round-trip are exercised through the UI the user sees,
 * with files seeded into the real app workspace (`filesDir/workspaces/app/work`).
 *
 * Covers the roadmap line end to end: 来源标识 (Workspace chip + current-source label), 路径面包屑
 * (root → region navigation), 名称/大小排序, 列表/网格视图, 多选, 文本预览 + MIME/大小/哈希, 重命名
 * 冲突 (询问 → 跳过, never a default overwrite), 删除到回收站/恢复/永久删除, 新建文件夹, and the
 * 分享 action's presence (its real [File] handoff is JVM-tested; the OS chooser is not asserted).
 * The long-operation 进度/取消 mechanics are covered by the JVM [FileManagerServiceTest].
 */
@RunWith(AndroidJUnit4::class)
class FilesScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var container: AppContainer

    /** The app workspace's real root on device (== AppContainer's `workspaces/app`). */
    private val wsRoot: File get() = composeRule.activity.filesDir.resolve("workspaces/app")

    @Before
    fun setUp() {
        container = (composeRule.activity.application as HelixApplication).appContainer
        // Deterministic, gate-free shell (dismisses the first-launch notice, re-arms STANDARD).
        composeRule.resetDeterministicUiState()
        // Start every test from a clean slate: clear the three user regions (files AND any
        // subdirectories a previous test created) plus the trash, then recreate the layout. Test
        // order is not guaranteed, so each test must leave no residue for the next — the files-only
        // delete that was here leaked a created folder (e.g. work/newdir) into the next test's listing.
        listOf("input", "work", "output").forEach { region ->
            val dir = wsRoot.resolve(region)
            dir.deleteRecursively()
            dir.mkdirs()
        }
        val trash = wsRoot.resolve(".helix").resolve("trash")
        trash.deleteRecursively()
        trash.mkdirs()
    }

    // ── 来源标识 + 路径面包屑 + 列表 ─────────────────────────────────────────────────────

    @Test
    fun listsWorkspaceSourceAndNavigatesByBreadcrumb() {
        seed("work/alpha.txt", "hello alpha")
        composeRule.navigateTo("files")

        // 来源标识: the always-present Workspace is the current (and, in consumer, only) source.
        waitTag("files-entry-work")
        composeRule.onNodeWithText("当前来源：Workspace").assertExists()

        // Root lists the three user regions; .helix is hidden.
        composeRule.onNodeWithTag("files-entry-input").assertExists()
        composeRule.onNodeWithTag("files-entry-output").assertExists()
        composeRule
            .onAllNodes(tagPrefix("files-entry-.helix"))
            .fetchSemanticsNodes()
            .isEmpty()
            .let { assertTrue(".helix must be hidden at the workspace root", it) }

        // 路径面包屑: drill into work/, then back out via the root crumb.
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-alpha.txt")
        composeRule.onNodeWithTag("files-breadcrumb-crumb-work").assertExists()

        composeRule.onNodeWithTag("files-breadcrumb-root").performClick()
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").assertExists()
    }

    // ── 排序 + 列表/网格视图 + 多选 ───────────────────────────────────────────────────────

    @Test
    fun sortsByNameAndSizeSwitchesViewAndMultiSelects() {
        seed("work/a.txt", "1") // 1 byte
        seed("work/b.txt", "222") // 3 bytes
        composeRule.navigateTo("files")
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-a.txt")

        // Default NAME sort: a.txt before b.txt. SIZE sort (desc): b.txt (3) before a.txt (1).
        assertEquals("files-entry-a.txt", entryTags().first())
        composeRule.onNodeWithTag("files-sort-SIZE").performClick()
        composeRule.waitUntil(5_000) { entryTags().firstOrNull() == "files-entry-b.txt" }
        assertEquals("files-entry-b.txt", entryTags().first())

        // 网格视图: the same entries re-render in the grid.
        composeRule.onNodeWithTag("files-view-grid").performClick()
        composeRule.waitUntil(5_000) { entryTags().size >= 2 }
        assertTrue(
            "grid still lists both entries",
            entryTags().containsAll(listOf("files-entry-a.txt", "files-entry-b.txt")),
        )

        // 多选: checking a box surfaces the batch action bar.
        composeRule.onNodeWithTag("files-select-a.txt").performClick()
        waitTag("files-batch-copy")
        composeRule.onNodeWithText("已选 1").assertExists()
        composeRule.onNodeWithTag("files-batch-move").assertExists()
        composeRule.onNodeWithTag("files-batch-trash").assertExists()
    }

    // ── 预览 + MIME/大小/哈希 ─────────────────────────────────────────────────────────────

    @Test
    fun previewsTextFileWithHashInfo() {
        seed("work/note.txt", "hello alpha")
        composeRule.navigateTo("files")
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-note.txt")

        composeRule.onNodeWithTag("files-entry-note.txt").performClick()
        waitTag("files-preview-text")

        assertEquals("hello alpha", nodeText("files-preview-text"))
        // 哈希信息: a real SHA-256 (64 hex chars) is shown.
        val sha = nodeText("files-info-sha")
        assertTrue(
            "hash line must be a SHA-256: $sha",
            sha.startsWith("SHA-256：") && sha.length >= "SHA-256：".length + 64,
        )
    }

    // ── 冲突: 重命名 onto an existing file → 询问, 跳过 never overwrites ─────────────────

    @Test
    fun renameConflictAsksAndSkipNeverOverwrites() {
        seed("work/a.txt", "keep-me-a")
        seed("work/b.txt", "keep-me-b")
        composeRule.navigateTo("files")
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-a.txt")

        composeRule.onNodeWithTag("files-entry-a.txt").performClick()
        waitTag("files-preview-dialog")
        composeRule.onNodeWithTag("files-action-rename").performClick()
        waitTag("files-rename-dialog")
        composeRule.onNodeWithTag("files-rename-field").performTextInput("b.txt")
        composeRule.onNodeWithTag("files-rename-confirm").performClick()

        // The rename onto the occupied b.txt must surface the 询问 dialog, not clobber it.
        waitTag("files-conflict-dialog")
        composeRule.onNodeWithTag("files-conflict-skip").performClick()
        composeRule.waitUntil(5_000) { nodeText("files-status").contains("已跳过") }
        assertTrue(nodeText("files-status").contains("已跳过"))

        // 禁止默认覆盖: neither file was touched by the skipped rename.
        assertEquals("keep-me-b", File(wsRoot, "work/b.txt").readText())
        assertEquals("keep-me-a", File(wsRoot, "work/a.txt").readText())
    }

    // ── trash: 删除到回收站 → 恢复 → 永久删除 ─────────────────────────────────────────────

    @Test
    fun trashesRestoresAndPurges() {
        seed("work/doomed.txt", "bye")
        composeRule.navigateTo("files")
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-doomed.txt")

        // 删除到回收站 (single-file delete goes through the batch path with progress).
        composeRule.onNodeWithTag("files-entry-doomed.txt").performClick()
        waitTag("files-preview-dialog")
        composeRule.onNodeWithTag("files-action-trash").performClick()
        composeRule.waitUntil(5_000) { nodeText("files-status").contains("完成") }

        // The file is now in the trash (not in the listing); the panel lists it.
        composeRule.onNodeWithTag("files-trash-open").performClick()
        waitTagPrefix("files-trash-entry-")
        composeRule.onNodeWithTag("files-trash-back").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("files-entry-doomed.txt").fetchSemanticsNodes().isEmpty()
        }

        // 恢复: back in the trash panel, restore the single entry → it returns to work/.
        composeRule.onNodeWithTag("files-trash-open").performClick()
        waitTagPrefix("files-trash-entry-")
        composeRule.onAllNodes(tagPrefix("files-trash-restore-"), true).onFirst().performClick()
        composeRule.waitUntil(5_000) { nodeText("files-status").contains("已恢复") }
        composeRule.onNodeWithTag("files-trash-back").performClick()
        waitTag("files-entry-doomed.txt")
        assertEquals("bye", File(wsRoot, "work/doomed.txt").readText())

        // 永久删除: trash again, then purge → the trash ends up empty.
        composeRule.onNodeWithTag("files-entry-doomed.txt").performClick()
        waitTag("files-preview-dialog")
        composeRule.onNodeWithTag("files-action-trash").performClick()
        composeRule.waitUntil(5_000) { nodeText("files-status").contains("完成") }
        composeRule.onNodeWithTag("files-trash-open").performClick()
        waitTagPrefix("files-trash-entry-")
        composeRule.onAllNodes(tagPrefix("files-trash-purge-"), true).onFirst().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(tagPrefix("files-trash-entry-"), true).fetchSemanticsNodes().isEmpty()
        }
    }

    // ── 新建文件夹 + 分享 action presence ────────────────────────────────────────────────

    @Test
    fun createsFolderAndExposesShare() {
        seed("work/share.txt", "share me")
        composeRule.navigateTo("files")
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-share.txt")

        // 新建文件夹.
        composeRule.onNodeWithTag("files-newfolder").performClick()
        waitTag("files-newfolder-dialog")
        composeRule.onNodeWithTag("files-newfolder-field").performTextInput("newdir")
        composeRule.onNodeWithTag("files-newfolder-confirm").performClick()
        waitTag("files-entry-newdir")

        // 分享: the action is present on a file's detail dialog (its FileProvider handoff is
        // JVM-tested; the OS chooser is intentionally not driven on-device).
        composeRule.onNodeWithTag("files-entry-share.txt").performClick()
        waitTag("files-preview-dialog")
        composeRule.onNodeWithTag("files-action-share").assertExists()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    /** Seeds a file into the app workspace under [relative] (e.g. "work/note.txt"). */
    private fun seed(
        relative: String,
        content: String,
    ) {
        val f = wsRoot.resolve(relative)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    private fun tagPrefix(prefix: String): SemanticsMatcher =
        SemanticsMatcher("tag-prefix:$prefix") { node -> tagOf(node).startsWith(prefix) }

    /** The `files-entry-*` node tags in current visual (tree) order — the first is the top row. */
    private fun entryTags(): List<String> =
        composeRule
            .onAllNodes(tagPrefix("files-entry-"), true)
            .fetchSemanticsNodes()
            .map { tagOf(it) }

    private fun nodeText(tag: String): String = textOf(composeRule.onNodeWithTag(tag).fetchSemanticsNode())

    private fun tagOf(node: SemanticsNode): String =
        if (node.config.contains(SemanticsProperties.TestTag)) node.config.get(SemanticsProperties.TestTag) else ""

    private fun textOf(node: SemanticsNode): String =
        node.config
            .getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString("") { it.text }

    companion object {
        /**
         * Budget for [waitTag]/[waitTagPrefix]. Each test cold-starts a fresh MainActivity
         * (JUnit4 rule), so the first wait absorbs app cold start + first frame on the
         * slowest supported emulator image (API 29). `waitUntil` returns as soon as the
         * condition holds, so healthy runs pay nothing for the budget. (The API 29
         * ComposeTimeoutExceptions first seen during HXA-048 were production defects —
         * `java.*` stdlib calls missing from the API 29 platform (`Stream.toList()` in
         * `WorkspaceArtifactStore.listDir`, API 31+; `InputStream.skipNBytes` in
         * `ReadWindow`) — not a budget problem; see
         * docs/bug-fixes/2026-09-03-jvm-stdlib-calls-missing-on-api29.md.)
         */
        private const val WAIT_TIMEOUT_MS = 30_000L
    }

    private fun waitTag(
        tag: String,
        timeoutMs: Long = WAIT_TIMEOUT_MS,
    ) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitTagPrefix(
        prefix: String,
        timeoutMs: Long = WAIT_TIMEOUT_MS,
    ) {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodes(tagPrefix(prefix), true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
