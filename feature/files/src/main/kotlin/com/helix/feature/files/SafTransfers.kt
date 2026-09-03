package com.helix.feature.files

import java.io.InputStream

/**
 * Reports the provider's (untrusted) [SafSourceMetadata] for a source document URI (HXA-058).
 * Production: [ContentResolverSafMetadataReader]. The facade reads it to SHOW the user the
 * source name/size before the copy; the import pipeline re-reads nothing from it and re-verifies
 * everything against the bytes that actually arrive (doc 07).
 */
fun interface SafSourceMetadataReader {
    fun metadata(uri: String): SafSourceMetadata
}

/**
 * Re-opens a destination document for a READ after an export (HXA-058 导出后重新读取校验).
 * @return the input stream, or null when the destination cannot be re-read (the export is then
 *   only platform-confirmed, never byte-verified — fail closed, not an error).
 * Production: [ContentResolverSafDestinationReReader].
 */
fun interface SafDestinationReReader {
    fun openStream(uri: String): InputStream?
}

/**
 * One FILE entry of a SAF document tree listing for import (HXA-058 文件夹导入). [rawSegments]
 * are the UNSANITIZED display names from the tree root to the file (each is untrusted provider
 * input — the planner sanitizes them before they become workspace path segments). [sizeBytes] is
 * the provider-reported size (-1 unknown), used for display/admission only. [documentUri] is the
 * opaque document URI the source opener consumes; it is never rendered, logged or passed to the
 * model (doc 10).
 */
data class SafTreeImportEntry(
    val rawSegments: List<String>,
    val sizeBytes: Long,
    val documentUri: String,
)

/**
 * A bounded, read-only enumeration of a SAF document tree's files (HXA-058). Only FILE entries
 * are returned (directories are implicit in [SafTreeImportEntry.rawSegments]). The enumeration is
 * bounded and fail-closed: a tree whose walk exceeds the bounds raises
 * [SafTreeEnumerationLimit] rather than silently omitting a subtree.
 *
 * Production: [ContentResolverSafTreeLister] (real `ContentResolver` / `DocumentsContract`
 * children queries over the picker's one-shot tree grant).
 */
interface SafTreeLister {
    fun listTree(treeUri: String): List<SafTreeImportEntry>
}

/**
 * Raised when a SAF tree walk exceeds its enumeration bounds (entry or depth cap). The import is
 * refused as a whole (fail closed — a truncated walk would silently omit files).
 */
class SafTreeEnumerationLimit : RuntimeException("SAF tree exceeds the enumeration limit")

/**
 * Resolves (creating when needed) the DESTINATION document of an export into a persisted,
 * user-authorized SAF tree (HXA-058 导出到已授权 tree). The grant itself is re-verified in WRITE
 * mode by the caller ([SafTreeScopeService.resolve]) before this seam is driven; this adapter
 * only maps the user-chosen (directory path, file name) onto the tree's opaque document ids.
 *
 * Conflict semantics (fail closed, never a silent overwrite): a same-name FILE already present in
 * [parentPath] raises [java.nio.file.FileAlreadyExistsException] unless [overwrite] is true
 * (then that document's URI is returned — the export's truncate-mode write IS the overwrite). A
 * missing parent or an AMBIGUOUS name raises [java.io.FileNotFoundException]. The returned URI is
 * opaque: it is consumed only by the export pipeline's destination opener.
 *
 * Production: [ContentResolverSafTreeDestination].
 */
interface SafTreeDestination {
    fun destinationUri(
        scopeId: String,
        parentPath: String,
        displayName: String,
        mimeType: String,
        overwrite: Boolean,
    ): String
}

/**
 * The restricted HXA-044 import/export pipelines plus the platform seams the file manager's
 * transfer entries drive (HXA-058 文件管理器导入/导出入口). Bundled so the app's facade receives
 * one governed object; none of its types carry a real path or a model-visible URI (doc 10).
 *
 * - [importPipeline]/[exportPipeline]: the HXA-044 fail-closed pipelines (reused as-is — the
 *   lying-provider defenses, the atomic publish, the region gate and the size re-check are the
 *   HXA-044 contract and are NOT relaxed here);
 * - [sourceMetadata]: untrusted source facts for the user-facing pre-copy display;
 * - [treeLister]: bounded tree enumeration for folder imports (picker one-shot grant);
 * - [treeDestination]: create/locate the destination document inside an authorized tree;
 * - [destinationReReader]: the post-export re-read evidence seam (verified = bytes re-read and
 *   hash-compared; otherwise only the platform-confirmed result is reported).
 */
class SafImportExportAccess(
    val importPipeline: SafImportPipeline,
    val exportPipeline: SafExportPipeline,
    val sourceMetadata: SafSourceMetadataReader,
    val treeLister: SafTreeLister,
    val treeDestination: SafTreeDestination,
    val destinationReReader: SafDestinationReReader,
)
