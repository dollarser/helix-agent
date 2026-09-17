package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.core.content.FileProvider
import com.helix.app.chat.ArtifactRowUi
import com.helix.app.files.ConflictPolicy
import com.helix.app.files.ExportTarget
import com.helix.app.files.FileManagerService
import com.helix.app.files.TransferItemStatus
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.feature.files.SafCancelToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HXA-203: the availability verdict for one artifact row, checked against the REAL file at
 * open (the row outlives the file — it can be deleted, edited, or its scope grant can be
 * revoked). [Missing] (file gone), [Revoked] (the scope grant no longer resolves —
 * fail-closed) and [Failed] (some other I/O failure) are three DIFFERENT honest states the
 * user sees; none of them is silently re-read or re-tried.
 */
internal sealed interface ArtifactAvailability {
    data object Loading : ArtifactAvailability

    /** The file is gone (deleted / moved / trash-purged) — an honest invalidation reason. */
    data object Missing : ArtifactAvailability

    /** The scope grant was revoked or no longer resolves; the file cannot be inspected. */
    data object Revoked : ArtifactAvailability

    /** The file could not be inspected for some other reason (I/O failure). */
    data object Failed : ArtifactAvailability

    data class Ready(
        val meta: FileManagerService.FileMeta,
        val text: String?,
        val imageBytes: ByteArray,
        /** True only on a POSITIVE mismatch with the task's recorded size/hash. */
        val changed: Boolean,
        /** True when the text preview holds only the beginning of a larger file. */
        val textTruncated: Boolean,
    ) : ArtifactAvailability
}

/**
 * HXA-203: the honest export outcome of the artifact file dialog — published ONLY from the
 * real pipeline TransferResult; COMPLETED carries the pipeline's own verified/size-checked
 * detail, CANCELLED reuses the files-export partial-outcome wording, never "exported".
 */
internal sealed interface ArtifactExportState {
    data object Idle : ArtifactExportState

    data object Running : ArtifactExportState

    data class Completed(
        val detail: String,
    ) : ArtifactExportState

    data object Cancelled : ArtifactExportState

    data class Failed(
        val detail: String,
    ) : ArtifactExportState
}

/**
 * Matches the Files facade's bounded text-preview cap (the [FileManagerService] preview
 * default, 64 KiB) so the dialog can say "beginning only" instead of silently showing a
 * head-truncated file as the whole file.
 */
internal const val ARTIFACT_TEXT_PREVIEW_CAP_BYTES = 64L * 1024

/**
 * The pure change decision behind [ArtifactAvailability.Ready.changed] (HXA-203: 缺文件、
 * 撤权、内容变化分别显示): a size mismatch, or a verifiable hash mismatch against the hash
 * the task recorded. A hash that could not be verified (file too large for hashing, I/O
 * failure) is NOT claimed as a change — the facade then returns no hash, and this must not
 * fabricate one.
 */
internal fun artifactAvailabilityChanged(
    row: ArtifactRowUi,
    meta: FileManagerService.FileMeta,
): Boolean {
    if (meta.sizeBytes != row.sizeBytes) return true
    val expected = row.sha256
    val actual = meta.sha256
    return expected != null && actual != null && actual != expected
}

/**
 * The scope part of the row's model reference (`scope:<scopeId>:<rel>`). Null when the
 * reference is not well-formed — the dialog then reports the honest unavailable state
 * instead of crashing the whole file list.
 */
internal fun ArtifactRowUi.parsedScopePath(): FileScopePath? =
    runCatching { FileScopePath.fromModelReference(relativePath) }.getOrNull()

/**
 * Inspect one artifact row against the real file (HXA-203). A revoked/unresolvable scope
 * ([ScopeNotAvailable]) is its own state, distinct from a deleted file ([Missing]) and from
 * a generic I/O failure ([Failed]) — the user must see WHICH invalidation happened.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal suspend fun inspectArtifactAvailability(
    fileManager: FileManagerService,
    row: ArtifactRowUi,
): ArtifactAvailability {
    val scopePath = row.parsedScopePath() ?: return ArtifactAvailability.Failed
    val scopeId = scopePath.scopeId
    val relPath = scopePath.relativePath
    return try {
        val meta = fileManager.fileInfo(scopeId, relPath)
        if (meta.sizeBytes < 0) {
            ArtifactAvailability.Missing
        } else {
            ArtifactAvailability.Ready(
                meta = meta,
                text = if (meta.isText) fileManager.previewText(scopeId, relPath) else null,
                imageBytes =
                    if (meta.mimeType.startsWith("image/")) {
                        fileManager.previewImageBytes(scopeId, relPath)
                    } else {
                        ByteArray(0)
                    },
                changed = artifactAvailabilityChanged(row, meta),
                textTruncated = meta.isText && meta.sizeBytes > ARTIFACT_TEXT_PREVIEW_CAP_BYTES,
            )
        }
    } catch (e: ScopeNotAvailable) {
        // Fail-closed: the SAF grant is gone (revoked) — a different honest state than Missing.
        ArtifactAvailability.Revoked
    } catch (e: Exception) {
        ArtifactAvailability.Failed
    }
}

/** The outcome of asking the OS to open an artifact in another app (HXA-203). */
internal enum class ArtifactExternalOpenResult {
    /** A chooser was launched; which app handles it is the OS's business. */
    Launched,

    /** No installed app handles the type; the user is told so, nothing is claimed. */
    NoViewer,

    /** Any other failure (staging copy, provider, activity launch). */
    Failed,
}

/**
 * HXA-203: open one artifact with ANOTHER app (ACTION_VIEW over the FileProvider uri with
 * read permission granted). The no-viewer case is decided by an explicit resolveActivity
 * pre-check: the framework chooser always resolves, so a type no app handles would otherwise
 * launch an empty chooser and read as "launched"; the manifest's VIEW queries declaration
 * keeps that check honest under API 30+ package visibility. [mimeOverride] lets a test force
 * a type no viewer resolves; production passes null and the type is probed from content.
 * Staging a SAF artifact copies it first, so call this off the main thread.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal fun openArtifactExternal(
    context: Context,
    fileManager: FileManagerService,
    scopeId: String,
    relativePath: String,
    mimeOverride: String? = null,
): ArtifactExternalOpenResult =
    try {
        val realFile = fileManager.realFileFor(scopeId, relativePath)
        val uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", realFile)
        val probed = mimeOverride ?: fileManager.mimeTypeFor(scopeId, relativePath)
        val mime = probed.ifEmpty { "application/octet-stream" }
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        if (intent.resolveActivity(context.packageManager) == null) {
            // No installed app handles this type: report it — an empty chooser would claim a
            // launch that no app can take.
            ArtifactExternalOpenResult.NoViewer
        } else {
            context.startActivity(Intent.createChooser(intent, null))
            ArtifactExternalOpenResult.Launched
        }
    } catch (e: ActivityNotFoundException) {
        ArtifactExternalOpenResult.NoViewer
    } catch (e: Exception) {
        ArtifactExternalOpenResult.Failed
    }

/**
 * HXA-203: run the artifact dialog's export through the SAME pipeline the Files page uses
 * ([FileManagerService.exportDocument]) and publish the outcome ONLY from the pipeline's
 * TransferResult — COMPLETED keeps the pipeline's own verified/size-checked/platform-only
 * detail, CANCELLED reuses the partial-outcome wording, anything else is a failure (an
 * empty detail reads as the generic export-failed text in the dialog). A structured
 * cancellation (dialog dismissed mid-run) also reads as cancelled, never as completed.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal fun startArtifactExport(
    scope: CoroutineScope,
    fileManager: FileManagerService,
    row: ArtifactRowUi,
    cancelFlag: MutableState<Boolean>,
    exportState: MutableState<ArtifactExportState>,
    destination: Uri,
) {
    val scopePath = row.parsedScopePath() ?: return
    if (exportState.value is ArtifactExportState.Running) return
    cancelFlag.value = false
    exportState.value = ArtifactExportState.Running
    scope.launch {
        try {
            val result =
                withContext(Dispatchers.IO) {
                    fileManager.exportDocument(
                        scopePath.relativePath,
                        ExportTarget.Document(
                            destination.toString(),
                            destination.lastPathSegment ?: row.fileName,
                        ),
                        ConflictPolicy.ASK,
                        SafCancelToken { cancelFlag.value },
                    ) { _, _ -> }
                }
            if (exportState.value is ArtifactExportState.Running) {
                val item = result.items.firstOrNull()
                exportState.value =
                    when (item?.status) {
                        TransferItemStatus.COMPLETED -> {
                            ArtifactExportState.Completed(item?.detail.orEmpty())
                        }

                        TransferItemStatus.CANCELLED -> {
                            ArtifactExportState.Cancelled
                        }

                        else -> {
                            ArtifactExportState.Failed(item?.detail.orEmpty())
                        }
                    }
            }
        } catch (e: CancellationException) {
            if (exportState.value is ArtifactExportState.Running) {
                exportState.value = ArtifactExportState.Cancelled
            }
            throw e
        } catch (e: Exception) {
            if (exportState.value is ArtifactExportState.Running) {
                exportState.value = ArtifactExportState.Failed(e.message.orEmpty())
            }
        }
    }
}
