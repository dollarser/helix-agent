package com.helix.app.eval

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.files.SystemSafPickerControls
import com.helix.app.ui.WorkspaceTreePicker
import com.helix.core.workspace.FileScopePath
import java.util.concurrent.atomic.AtomicReference

/** Owns only a new exact system-picker grant and synthetic descendants, never a native path. */
internal class SafModelFixture private constructor(
    private val app: HelixApplication,
    private val c: AppContainer,
    private val tree: Uri,
    private val root: Uri,
    private val scope: String,
    private val before: Set<String>,
) {
    val reference = FileScopePath(scope, "").toModelReference()
    private val directories = mutableMapOf("" to root)

    fun put(
        path: String,
        text: String,
    ) {
        val segments = path.split('/')
        var parent = root
        var prefix = ""
        segments.dropLast(1).forEach { name ->
            prefix = listOf(prefix, name).filter { it.isNotEmpty() }.joinToString("/")
            parent =
                directories.getOrPut(prefix) {
                    requireNotNull(
                        DocumentsContract.createDocument(
                            app.contentResolver,
                            parent,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            name,
                        ),
                    )
                }
        }
        val doc =
            requireNotNull(
                DocumentsContract.createDocument(
                    app.contentResolver,
                    parent,
                    "application/octet-stream",
                    segments.last(),
                ),
            )
        requireNotNull(app.contentResolver.openOutputStream(doc, "wt")).use { it.write(text.toByteArray()) }
    }

    fun close() {
        try {
            directories.filterKeys { it.isNotEmpty() && '/' !in it }.values.forEach {
                check(DocumentsContract.deleteDocument(app.contentResolver, it))
            }
        } finally {
            check(c.safTree.revoke(scope))
            app.contentResolver.releasePersistableUriPermission(tree, FLAGS)
            check(signature(app) == before)
        }
    }

    companion object {
        private const val AUTHORITY = "com.android.externalstorage.documents"
        private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        private fun signature(app: HelixApplication) =
            app.contentResolver.persistedUriPermissions
                .map {
                    "${it.uri}|${it.isReadPermission}|${it.isWritePermission}"
                }.toSet()

        fun open(
            compose: ComposeContentTestRule,
            app: HelixApplication,
            c: AppContainer,
            folder: String,
            suffix: String,
        ): SafModelFixture {
            require(folder.matches(Regex("Helix-reliability-[0-9a-f]{8}")))
            require(suffix.isNotEmpty())
            val id = "primary:Documents/$folder"
            val before = signature(app)
            val chosen = AtomicReference<Intent?>()
            compose.setContent {
                val picker = rememberLauncherForActivityResult(WorkspaceTreePicker()) { chosen.set(it) }
                Button(onClick = {
                    picker.launch(DocumentsContract.buildDocumentUri(AUTHORITY, id))
                }, modifier = Modifier.testTag("saf-model-open")) { Text("Open") }
            }
            compose.onNodeWithTag("saf-model-open").performClick()
            SystemSafPickerControls().selectExactFolder(folder)
            compose.waitUntil(15_000) { chosen.get() != null }
            val result = requireNotNull(chosen.get())
            val tree = requireNotNull(result.data)
            check(tree.authority == AUTHORITY && DocumentsContract.getTreeDocumentId(tree) == id)
            check(result.flags and FLAGS == FLAGS)
            check(app.contentResolver.persistedUriPermissions.none { it.uri == tree })
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            requireNotNull(
                app.contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null,
                    null,
                    null,
                ),
            ).use {
                check(!it.moveToFirst()) { "Only a new empty synthetic directory is permitted" }
            }
            app.contentResolver.takePersistableUriPermission(tree, FLAGS)
            val scope = c.safTree.grant(tree.toString(), folder).scopeId
            return SafModelFixture(app, c, tree, DocumentsContract.buildDocumentUriUsingTree(tree, id), scope, before)
        }
    }
}
