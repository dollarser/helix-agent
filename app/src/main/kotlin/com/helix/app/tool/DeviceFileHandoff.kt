package com.helix.app.tool

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.helix.app.files.ShareCancelled
import com.helix.app.files.ShareSnapshotCache
import com.helix.core.model.CancelSignal
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.IOException

/** Copies through the normal scoped store; never grants another app access to a mutable source. */
internal class DeviceFileHandoff(
    private val context: Context,
    private val store: WorkspaceArtifactStore,
) : DeviceFileTools.Handoff {
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Fail closed; no exception is reported as delivery.
    override fun launch(paths: List<FileScopePath>, open: Boolean, cancel: CancelSignal): ToolExecutorResult {
        var directory: File? = null
        var launched = false
        try {
            val snapshot =
                ShareSnapshotCache(File(context.cacheDir, "device-share")).stage(
                    paths.map { path -> ShareSnapshotCache.Source(path.name) { store.openRead(path) } },
                    MAX_BYTES,
                    cancel::isCancelled,
                )
            directory = snapshot.directory
            val intent = handoffIntent(snapshot.files, paths.map { it.name }, open)
            if (cancel.isCancelled()) return ToolExecutorResult.Cancelled
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            launched = true
            return ToolExecutorResult.Completed(
                buildJsonObject {
                    put("status", JsonPrimitive("chooser-opened"))
                    put("reason", JsonPrimitive(""))
                },
            )
        } catch (_: ShareCancelled) {
            return ToolExecutorResult.Cancelled
        } catch (_: ActivityNotFoundException) {
            return ToolExecutorResult.Completed(
                buildJsonObject {
                    put("status", JsonPrimitive("no-handler"))
                    put("reason", JsonPrimitive("No application can handle these files"))
                },
            )
        } catch (_: IOException) {
            return ToolExecutorResult.Failed("Unable to read or stage the authorized files")
        } catch (_: Exception) {
            return ToolExecutorResult.Failed("File handoff refused or unavailable")
        } finally {
            // Once handed off, Android owns the temporary read grant; cache survives until OS eviction.
            if (!launched) directory?.deleteRecursively()
        }
    }

    private fun handoffIntent(
        files: List<File>,
        names: List<String>,
        open: Boolean,
    ): Intent {
        val uris =
            files.mapIndexed { index, file ->
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, names[index])
            }
        val mimes = files.map(ShareSnapshotCache::mime)
        val mime = if (mimes.distinct().size == 1) mimes.first() else "*/*"
        val action =
            when {
                open -> Intent.ACTION_VIEW
                uris.size == 1 -> Intent.ACTION_SEND
                else -> Intent.ACTION_SEND_MULTIPLE
            }
        return Intent(action).apply {
            type = mime
            when {
                open -> setDataAndType(uris.single(), mime)
                uris.size == 1 -> putExtra(Intent.EXTRA_STREAM, uris.single())
                else -> putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            clipData =
                ClipData.newUri(context.contentResolver, "Helix files", uris.first()).also { clip ->
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
