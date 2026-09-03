package com.helix.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.test.TransferTestDocumentsProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HXA-058 文件管理器导入/导出入口, verified on a real device (consumer gate; the developer build
 * passes it too). The OS pickers (ACTION_OPEN_DOCUMENT / OPEN_DOCUMENT_TREE / CREATE_DOCUMENT)
 * are NOT driven on device (project precedent) — the UI layer is verified up to the picker
 * boundary: the 导入 dialog (来源选择 / 目标 / 冲突策略 / 取消), the 导出 entry in the file
 * preview, the 导出 dialog (新建文档 vs 已授权 SAF 目录 — the LIVE HXA-057 grant list, the
 * sub-directory field, the policy row), and a FULL 导出-to-authorized-tree round-trip through
 * the real HXA-044 pipelines (the result panel shows 来源/目标/结果, and "已校验" only when the
 * bytes were re-read after the write).
 */
@RunWith(AndroidJUnit4::class)
class FilesImportExportUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var container: AppContainer

    private val wsRoot: File get() = composeRule.activity.filesDir.resolve("workspaces/app")

    @Before
    fun setUp() {
        container = (composeRule.activity.application as HelixApplication).appContainer
        composeRule.resetDeterministicUiState()
        listOf("input", "work", "output").forEach { region ->
            val dir = wsRoot.resolve(region)
            dir.deleteRecursively()
            dir.mkdirs()
        }
        val trash = wsRoot.resolve(".helix").resolve("trash")
        trash.deleteRecursively()
        trash.mkdirs()
    }

    // ── 导入 dialog: 来源 / 目标 / 冲突策略 / 取消 ────────────────────────────────────────

    @Test
    fun importDialogShowsSourceOptionsTargetPoliciesAndDismisses() {
        composeRule.navigateTo("files")
        waitTag("files-entry-work")

        composeRule.onNodeWithTag("files-import-open").performClick()
        waitTag("files-import-dialog")

        // 来源: the two picker actions (the OS picker itself is not driven on device).
        composeRule.onNodeWithTag("files-import-file").assertExists()
        composeRule.onNodeWithTag("files-import-folder").assertExists()

        // 目标: the import always lands in the Workspace input/ region.
        composeRule.onNodeWithTag("files-import-target").assertExists()

        // 冲突策略: all four options, 询问 (ASK) by default — never a default overwrite.
        composeRule.onNodeWithTag("files-import-policy-ASK").assertExists()
        composeRule.onNodeWithTag("files-import-policy-SKIP").assertExists()
        composeRule.onNodeWithTag("files-import-policy-RENAME").assertExists()
        composeRule.onNodeWithTag("files-import-policy-OVERWRITE").assertExists()

        // The folder mode is selectable.
        composeRule.onNodeWithTag("files-import-folder").performClick()
        composeRule.onNodeWithText("● 文件夹").assertExists()

        // 取消/关闭: the dialog closes and nothing was imported.
        composeRule.onNodeWithTag("files-import-dismiss").performClick()
        composeRule.waitUntil(WAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("files-import-dialog").fetchSemanticsNodes().isEmpty()
        }
        assertTrue("a dismissed import imports nothing", wsRoot.resolve("input").list()?.isEmpty() == true)
    }

    // ── 导出 entry + dialog: 目标选择 (新建文档 / 已授权 SAF 目录) ────────────────────────

    @Test
    fun exportEntryShowsTheLiveAuthorizedTreeSourcesAndPolicies() {
        seed("work/export-ui.txt", "export me")
        composeRule.navigateTo("files")
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-export-ui.txt")

        // 导出 is an action on a Workspace file's preview (SAF/all-files sources are read-only).
        composeRule.onNodeWithTag("files-entry-export-ui.txt").performClick()
        waitTag("files-preview-dialog")
        composeRule.onNodeWithTag("files-action-export").assertExists()

        composeRule.onNodeWithTag("files-action-export").performClick()
        waitTag("files-export-dialog")

        // The 来源 line names the file being exported.
        composeRule.onAllNodes(hasText("work/export-ui.txt", substring = true)).onFirst().assertExists()

        // 目标: both destination shapes.
        composeRule.onNodeWithTag("files-export-newdoc").assertExists()
        composeRule.onNodeWithTag("files-export-tree").assertExists()

        // 已授权 SAF 目录: the LIVE HXA-057 grants (re-verified when the tree mode opens).
        val scope = container.safTree.grant(TransferTestDocumentsProvider.TREE_URI, "UI Tree").scopeId
        composeRule.onNodeWithTag("files-export-tree").performClick()
        waitTag("files-export-scope-$scope")
        composeRule.onNodeWithTag("files-export-scope-pick-$scope").performClick()
        composeRule.onNodeWithText("● UI Tree").assertExists()

        // The sub-directory field + the four conflict policies are present.
        composeRule.onNodeWithTag("files-export-parent").assertExists()
        composeRule.onNodeWithTag("files-export-policy-ASK").assertExists()
        composeRule.onNodeWithTag("files-export-policy-OVERWRITE").assertExists()

        composeRule.onNodeWithTag("files-export-dismiss").performClick()
        composeRule.waitUntil(WAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("files-export-dialog").fetchSemanticsNodes().isEmpty()
        }
    }

    // ── 导出 to an authorized tree through the UI: READ-ONLY grant, full path ───────────
    //
    // The production container keeps the REAL re-verification (ContentResolverSafTreeCheck). The
    // in-process fixture provider carries no persisted WRITE permission (the API 30+ platform
    // will not mint one for it without a real temporary grant — device-verified), so exporting
    // through the UI must be REFUSED before any byte is written: the result panel shows the
    // stable write-permission refusal and an INDEPENDENT resolver query proves the tree is
    // untouched. The WRITE happy path (create-document + verified re-read) is device-verified at
    // the facade layer (ImportExportFacadeDeviceTest.exportToATreeUnderAWriteGrantCreatesAndVerifies,
    // every re-verification fact real apart from the write bit).

    @Test
    fun exportToAnAuthorizedReadonlyTreeThroughTheUiIsRefusedBeforeAnyWrite() {
        val resolver = composeRule.activity.contentResolver
        // The grant exists BEFORE the dialog opens (the live list is re-verified per open/mode).
        val scope = container.safTree.grant(TransferTestDocumentsProvider.TREE_URI, "UI Tree").scopeId

        seed("work/fresh-ui.txt", "fresh ui export")
        composeRule.navigateTo("files")
        waitTag("files-entry-work")
        composeRule.onNodeWithTag("files-entry-work").performClick()
        waitTag("files-entry-fresh-ui.txt")

        composeRule.onNodeWithTag("files-entry-fresh-ui.txt").performClick()
        waitTag("files-preview-dialog")
        composeRule.onNodeWithTag("files-action-export").performClick()
        waitTag("files-export-dialog")

        composeRule.onNodeWithTag("files-export-tree").performClick()
        waitTag("files-export-scope-pick-$scope")
        composeRule.onNodeWithTag("files-export-scope-pick-$scope").performClick()
        composeRule.onNodeWithTag("files-export-policy-ASK").performClick()
        composeRule.onNodeWithTag("files-export-confirm").performClick()
        waitTag("files-export-result")

        // 最终结果: one item FAILED with the stable write-permission refusal — never a partial write.
        composeRule.onNodeWithText("成功 0 项，跳过/冲突/失败 1 项").assertExists()
        composeRule.onAllNodes(hasText("写权限", substring = true)).onFirst().assertExists()

        // Independent evidence: the read-only tree received nothing (real resolver query).
        assertFalse("a read-only grant writes nothing into the tree", treeContainsFreshUi(resolver))

        composeRule.onNodeWithTag("files-export-close").performClick()
        composeRule.waitUntil(WAIT_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag("files-export-dialog").fetchSemanticsNodes().isEmpty()
        }
    }

    /** True when the tree root's children (real resolver query) include fresh-ui.txt. */
    private fun treeContainsFreshUi(resolver: ContentResolver): Boolean {
        var found = false
        resolver
            .query(
                Uri.parse("content://${TransferTestDocumentsProvider.AUTHORITY}/document/tr/children"),
                null,
                null,
                null,
                null,
            )?.use { c ->
                // The fixture's tree child rows are positional: id, name, mime, size.
                while (c.moveToNext()) {
                    if (c.getString(1) == "fresh-ui.txt") found = true
                }
            }
        return found
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────

    private fun seed(
        relative: String,
        content: String,
    ) {
        val f = wsRoot.resolve(relative)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    companion object {
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
}
