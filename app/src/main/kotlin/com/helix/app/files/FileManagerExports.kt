package com.helix.app.files

import com.helix.app.R
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.ExportRefusal
import com.helix.feature.files.ExportStatus
import com.helix.feature.files.SafAccessMode
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafExportOutcome
import com.helix.feature.files.SafExportVerifier
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafTreeScopeAccess
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** User-requested export; owns live WRITE checks, destination conflicts and verification. */
@Suppress("TooManyFunctions")
internal class FileManagerExports(
    private val store: WorkspaceArtifactStore,
    private val workspaceScopeId: String,
    private val transfers: SafImportExportAccess,
    private val strings: (Int, Array<out Any>) -> String,
    private val saf: SafTreeScopeAccess?,
) {
    /** Localizes a stable string-resource id (+ positional args) to the current locale (HXA-069). */
    private fun loc(
        id: Int,
        vararg args: Any,
    ): String = strings(id, args)

    /**
     * Exports ONE workspace file to [target]. The source must be inside
     * `input/`/`work/`/`output/` (the HXA-044 region gate, unchanged). For a
     * [ExportTarget.TreeDestination] the grant is re-verified in WRITE mode in real time (a
     * read-only or revoked grant fails closed before any byte is written) and the document is
     * created in the tree under the chosen conflict policy (never a default overwrite).
     *
     * The result reports the platform-confirmed facts, and "verified" ONLY when the bytes were
     * re-read after the write and are hash-equal.
     */
    @Suppress(
        "ReturnCount",
        "LongMethod",
        "TooGenericExceptionCaught",
        "SwallowedException",
    ) // one fail-closed exit per fault path; HXA-069 i18n wraps pushed the body past 60
    fun exportDocument(
        sourceRelativePath: String,
        target: ExportTarget,
        policy: ConflictPolicy,
        cancel: SafCancelToken,
        onProgress: (
            Long,
            Long,
        ) -> Unit,
    ): TransferResult {
        val source = FileScopePath(workspaceScopeId, sourceRelativePath)
        val resolved =
            when (target) {
                is ExportTarget.Document -> {
                    ExportDestinationResolved(target.uri, target.label)
                }

                is ExportTarget.TreeDestination -> {
                    val access: SafTreeScopeAccess =
                        try {
                            verifiedWriteAccess(target.scopeId)
                        } catch (e: ScopeNotAvailable) {
                            return TransferResult(
                                listOf(
                                    TransferItem(
                                        sourceRelativePath,
                                        loc(R.string.files_transfer_saf_source, target.scopeId),
                                        TransferItemStatus.FAILED,
                                        loc(R.string.files_export_detail_saf_not_writable),
                                    ),
                                ),
                                0,
                            )
                        }
                    val destDir = target.parentPath.ifEmpty { loc(R.string.files_transfer_root_dir) }
                    val scopeName = access.service.source(target.scopeId)?.displayName ?: target.scopeId
                    val label = loc(R.string.files_transfer_saf_source, "$scopeName/$destDir")
                    val name = sourceRelativePath.substringAfterLast('/')
                    val mime =
                        try {
                            store.probe(source).mimeType
                        } catch (e: Exception) {
                            "application/octet-stream"
                        }
                    when (val r = resolveTreeDestination(transfers, access, target, name, mime, policy)) {
                        is TreeDestinationResolved -> {
                            ExportDestinationResolved(r.uri, "$label/${r.finalName}")
                        }

                        is TreeDestinationConflict -> {
                            ExportDestinationConflict(label, loc(R.string.files_export_detail_saf_exists_conflict))
                        }

                        is TreeDestinationSkipped -> {
                            ExportDestinationSkipped(label, loc(R.string.files_export_detail_saf_exists_skipped))
                        }

                        is TreeDestinationFailure -> {
                            ExportDestinationFailed(label, r.detail)
                        }
                    }
                }
            }
        val (destUri, destLabel) =
            when (resolved) {
                is ExportDestinationResolved -> resolved.uri to resolved.label
                is ExportDestinationConflict -> return problemResult(sourceRelativePath, resolved)
                is ExportDestinationFailed -> return problemResult(sourceRelativePath, resolved)
                is ExportDestinationSkipped -> return problemResult(sourceRelativePath, resolved)
            }
        val outcome = transfers.exportPipeline.exportFile(source, destUri, cancel, onProgress)
        return TransferResult(listOf(mapExportOutcome(outcome, sourceRelativePath, destLabel, destUri, transfers)), 0)
    }

    /** The SAF access with a WRITE-mode re-verified grant (fail closed), or [ScopeNotAvailable]. */
    private fun verifiedWriteAccess(scopeId: String): SafTreeScopeAccess {
        val access = saf ?: throw ScopeNotAvailable("SAF tree scope not available: $scopeId")
        access.service.resolve(scopeId, SafAccessMode.WRITE)
        return access
    }

    /** The conflict-aware resolution of an export destination inside an authorized tree. */
    @Suppress("ReturnCount", "SwallowedException") // each platform error becomes one stable refusal
    private fun resolveTreeDestination(
        transfers: SafImportExportAccess,
        access: SafTreeScopeAccess,
        target: ExportTarget.TreeDestination,
        name: String,
        mime: String,
        policy: ConflictPolicy,
    ): TreeDestinationResolution {
        var current = name
        var attempts = 0
        while (true) {
            try {
                val uri = createInTree(transfers, target, current, mime, overwrite = false)
                return TreeDestinationResolved(uri, current)
            } catch (e: FileAlreadyExistsException) {
                when (val step = onTreeConflict(access, target, current, policy, attempts)) {
                    is Overwrite -> {
                        return TreeDestinationResolved(
                            createInTree(transfers, target, current, mime, overwrite = true),
                            current,
                        )
                    }

                    is Rename -> {
                        attempts++
                        current = step.nextName
                    }

                    is Conflict -> {
                        return TreeDestinationConflict
                    }

                    is Skipped -> {
                        return TreeDestinationSkipped
                    }

                    is GiveUp -> {
                        return TreeDestinationFailure(loc(R.string.files_export_detail_no_free_name))
                    }
                }
            } catch (e: FileNotFoundException) {
                return TreeDestinationFailure(loc(R.string.files_export_detail_saf_dir_missing))
            } catch (e: ScopeNotAvailable) {
                return TreeDestinationFailure(loc(R.string.files_export_detail_saf_unavailable))
            } catch (e: IOException) {
                return TreeDestinationFailure(loc(R.string.files_export_detail_saf_create_failed))
            }
        }
    }

    /** Creates (or, for [overwrite], reuses) the [displayName] document in the target directory. */
    private fun createInTree(
        transfers: SafImportExportAccess,
        target: ExportTarget.TreeDestination,
        displayName: String,
        mime: String,
        overwrite: Boolean,
    ): String =
        transfers.treeDestination.destinationUri(
            target.scopeId,
            target.parentPath,
            displayName,
            mime,
            overwrite = overwrite,
        )

    /** The next step when the destination name already exists in the tree (never a default overwrite). */
    private fun onTreeConflict(
        access: SafTreeScopeAccess,
        target: ExportTarget.TreeDestination,
        current: String,
        policy: ConflictPolicy,
        attempts: Int,
    ): ConflictStep =
        when (policy) {
            ConflictPolicy.OVERWRITE -> {
                Overwrite
            }

            ConflictPolicy.ASK -> {
                Conflict
            }

            ConflictPolicy.SKIP -> {
                Skipped
            }

            ConflictPolicy.RENAME -> {
                if (attempts + 1 > MAX_RENAME_ATTEMPTS) {
                    GiveUp
                } else {
                    Rename(nextRenamedName(access, target.scopeId, target.parentPath, current))
                }
            }
        }

    /**
     * The next free sibling of [current] under the RENAME policy. The tree's LIVE listing is the
     * truth for what already exists; an unlistable parent defers to create-document's own
     * same-name check (a RENAME that collides fails closed there, it never clobbers).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // an unlistable parent defers to the create check
    private fun nextRenamedName(
        access: SafTreeScopeAccess,
        scopeId: String,
        parentPath: String,
        current: String,
    ): String {
        val existing =
            try {
                access.reader
                    .list(scopeId, parentPath)
                    .map { it.name }
                    .toSet()
            } catch (e: Exception) {
                emptySet()
            }
        var i = 1
        while (suffixedName(current, i) in existing) i++
        return suffixedName(current, i)
    }

    private fun suffixedName(
        name: String,
        i: Int,
    ): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            "${name.substring(0, dot)} ($i)${name.substring(dot)}"
        } else {
            "$name ($i)"
        }
    }

    private sealed class TreeDestinationResolution

    private data class TreeDestinationResolved(
        val uri: String,
        val finalName: String,
    ) : TreeDestinationResolution()

    private data object TreeDestinationConflict : TreeDestinationResolution()

    private data object TreeDestinationSkipped : TreeDestinationResolution()

    private data class TreeDestinationFailure(
        val detail: String,
    ) : TreeDestinationResolution()

    private sealed class ExportDestinationOutcome

    private data class ExportDestinationResolved(
        val uri: String,
        val label: String,
    ) : ExportDestinationOutcome()

    /** A destination that could NOT be resolved to a writable document (never a silent clobber). */
    private sealed class ExportDestinationProblem : ExportDestinationOutcome() {
        abstract val label: String
        abstract val detail: String
    }

    private data class ExportDestinationConflict(
        override val label: String,
        override val detail: String,
    ) : ExportDestinationProblem()

    private data class ExportDestinationFailed(
        override val label: String,
        override val detail: String,
    ) : ExportDestinationProblem()

    private data class ExportDestinationSkipped(
        override val label: String,
        override val detail: String,
    ) : ExportDestinationProblem()

    /** The next action after a same-name collision inside an authorized tree. */
    private sealed class ConflictStep

    private object Overwrite : ConflictStep()

    private data class Rename(
        val nextName: String,
    ) : ConflictStep()

    private object Conflict : ConflictStep()

    private object Skipped : ConflictStep()

    private object GiveUp : ConflictStep()

    /** The fail-closed result for an export destination that could not be resolved. */
    private fun problemResult(
        sourceRelativePath: String,
        problem: ExportDestinationProblem,
    ): TransferResult {
        val status =
            when (problem) {
                is ExportDestinationConflict -> TransferItemStatus.CONFLICT
                is ExportDestinationSkipped -> TransferItemStatus.SKIPPED
                else -> TransferItemStatus.FAILED
            }
        return TransferResult(
            listOf(TransferItem(sourceRelativePath, problem.label, status, problem.detail)),
            0,
        )
    }

    private fun mapExportOutcome(
        outcome: SafExportOutcome,
        sourceRelativePath: String,
        destLabel: String,
        destUri: String,
        transfers: SafImportExportAccess,
    ): TransferItem =
        when (outcome.status) {
            ExportStatus.COMPLETED -> {
                // Verified ONLY when the bytes are re-read after the write and hash-equal;
                // otherwise only the platform-confirmed result is reported (roadmap HXA-058).
                val verified =
                    SafExportVerifier.reReadVerified(
                        destUri,
                        transfers.destinationReReader,
                        outcome.sha256,
                        outcome.sizeBytes,
                    )
                TransferItem(
                    sourceRelativePath,
                    destLabel,
                    TransferItemStatus.COMPLETED,
                    if (verified) {
                        loc(R.string.files_export_detail_verified)
                    } else if (outcome.sizeVerified) {
                        loc(R.string.files_export_detail_size_checked)
                    } else {
                        loc(R.string.files_export_detail_platform_only)
                    },
                    outcome.sizeBytes,
                    outcome.sha256,
                    sizeVerified = outcome.sizeVerified,
                    verified = verified,
                )
            }

            ExportStatus.CANCELLED -> {
                TransferItem(
                    sourceRelativePath,
                    destLabel,
                    TransferItemStatus.CANCELLED,
                    loc(R.string.files_export_cancelled_partial),
                )
            }

            ExportStatus.REFUSED -> {
                TransferItem(
                    sourceRelativePath,
                    destLabel,
                    TransferItemStatus.FAILED,
                    exportDetail(outcome.refusal),
                )
            }
        }

    private fun exportDetail(refusal: ExportRefusal?): String =
        when (refusal) {
            ExportRefusal.OUTSIDE_USER_REGIONS -> loc(R.string.files_export_refusal_outside_regions)
            ExportRefusal.SCOPE_UNAVAILABLE -> loc(R.string.files_workspace_unavailable)
            ExportRefusal.SOURCE_NOT_FOUND -> loc(R.string.files_error_source_missing)
            ExportRefusal.NOT_A_FILE -> loc(R.string.files_export_refusal_not_a_file)
            ExportRefusal.SOURCE_EXCEEDS_LIMIT -> loc(R.string.files_export_refusal_too_large)
            ExportRefusal.DESTINATION_UNOPENABLE -> loc(R.string.files_export_refusal_dest_unopenable)
            ExportRefusal.IO_FAILURE -> loc(R.string.files_export_refusal_io)
            ExportRefusal.SIZE_VERIFICATION_MISMATCH -> loc(R.string.files_export_refusal_size_mismatch)
            null -> loc(R.string.files_export_failed)
        }

    private companion object {
        const val MAX_RENAME_ATTEMPTS = 1000
    }
}
