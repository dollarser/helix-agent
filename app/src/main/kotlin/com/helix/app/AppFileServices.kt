package com.helix.app

import android.content.Context
import com.helix.app.files.FileManagerService
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.ContentResolverSafDestinationOpener
import com.helix.feature.files.ContentResolverSafDestinationReReader
import com.helix.feature.files.ContentResolverSafDestinationVerifier
import com.helix.feature.files.ContentResolverSafGrantProbe
import com.helix.feature.files.ContentResolverSafMetadataReader
import com.helix.feature.files.ContentResolverSafSourceOpener
import com.helix.feature.files.ContentResolverSafTreeCheck
import com.helix.feature.files.ContentResolverSafTreeDestination
import com.helix.feature.files.ContentResolverSafTreeLister
import com.helix.feature.files.ContentResolverSafTreeReader
import com.helix.feature.files.SafExportPipeline
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafTreeScopeAccess
import com.helix.feature.files.SafTreeScopeService

/**
 * SAF file import/export bundle (HXA-044, platform adapter layer). [SafGrantStore] holds the
 * persisted tree grants whose `content://` URIs never reach the model (doc 10: 模型只看到
 * scopeId); the ContentResolver adapters implement the pipeline seams; the pipelines are
 * fail-closed against a lying provider (doc 07).
 */
@Suppress("LongParameterList") // each adapter is an independently injected pipeline seam
class FeatureFiles(
    val grantStore: SafGrantStore,
    val metadataReader: ContentResolverSafMetadataReader,
    val sourceOpener: ContentResolverSafSourceOpener,
    val importPipeline: SafImportPipeline,
    val exportPipeline: SafExportPipeline,
    val destinationOpener: ContentResolverSafDestinationOpener,
    val destinationVerifier: ContentResolverSafDestinationVerifier,
    val grantProbe: ContentResolverSafGrantProbe,
    // HXA-058: the file-manager transfer seams (folder enumeration, tree-destination creation,
    // post-export re-read). Same ContentResolver, same fail-closed adapter layer.
    val treeLister: ContentResolverSafTreeLister,
    val treeDestination: ContentResolverSafTreeDestination,
    val destinationReReader: ContentResolverSafDestinationReReader,
)

/** Builds the existing file/SAF adapters against one workspace and one grant store. */
internal class AppFileServices(
    context: Context,
    scopeRoots: ScopeRootResolver,
    workspaceStore: WorkspaceArtifactStore,
    appScopeId: String,
    strings: (Int, Array<out Any>) -> String,
) {
    private val safGrantStore: SafGrantStore =
        SafGrantStore(java.io.File(context.filesDir, "workspaces/saf-grants.json").toPath())

    /**
     * The SAF tree scope service (HXA-057: persisted SAF tree scope 接线). Re-verifies every grant
     * in real time (grant / provider identity / root document / read-write mode) and fails closed on
     * revocation, provider-gone, restart, read-only grant or URI change. The file manager consumes
     * it read-only; the model and tools see only the model-opaque `scopeId` + relative path.
     */
    val safTree: SafTreeScopeService =
        SafTreeScopeService(safGrantStore, ContentResolverSafTreeCheck(context.contentResolver))

    /**
     * The SAF tree scope access the file manager consumes (HXA-057): the scope service + the
     * read-only `DocumentsContract` browse backend + an app-private share-staging directory. SAF
     * scopes are browse/preview/share-only in this milestone (the all-files precedent: mutations
     * hidden; a read-only grant's write re-verification fails closed regardless).
     */
    private val safAccess: SafTreeScopeAccess =
        SafTreeScopeAccess(
            service = safTree,
            reader = ContentResolverSafTreeReader(context.contentResolver, safGrantStore),
            shareDir = java.io.File(context.filesDir, "workspaces/saf-share").toPath(),
        )

    /**
     * SAF adapter bundle (HXA-044 + HXA-058; PRD: SAF scope 默认复制到应用私有目录处理). Reuses the
     * shared [safGrantStore] (HXA-057); the import pipeline targets the app workspace `input/`
     * region through the same [scopeRoots] the file tools use, and the export pipeline reads from
     * that scope's user regions. The HXA-058 seams add folder enumeration (picker one-shot grant),
     * tree-destination creation (persisted authorized tree) and the post-export re-read.
     */
    val featureFiles: FeatureFiles =
        run {
            val resolver = context.contentResolver
            val sourceOpener = ContentResolverSafSourceOpener(resolver)
            val destinationOpener = ContentResolverSafDestinationOpener(resolver)
            val destinationVerifier = ContentResolverSafDestinationVerifier(resolver)
            FeatureFiles(
                grantStore = safGrantStore,
                metadataReader = ContentResolverSafMetadataReader(resolver),
                sourceOpener = sourceOpener,
                importPipeline = SafImportPipeline(scopeRoots, sourceOpener),
                exportPipeline = SafExportPipeline(scopeRoots, destinationOpener, destinationVerifier),
                destinationOpener = destinationOpener,
                destinationVerifier = destinationVerifier,
                grantProbe = ContentResolverSafGrantProbe(resolver),
                treeLister = ContentResolverSafTreeLister(resolver),
                treeDestination = ContentResolverSafTreeDestination(resolver, safGrantStore),
                destinationReReader = ContentResolverSafDestinationReReader(resolver),
            )
        }

    /**
     * The file-manager facade (HXA-046 + HXA-057 + HXA-058): shares the tool pipeline's
     * [workspaceStore] and the same [scopeRoots] scope boundary, so the user's file manager and
     * the model's `files.*` tools address the identical, containment-enforced store. [appScopeId]
     * is the always-present, mutable workspace; all-files roots (developer) are appended read-only,
     * and SAF tree scopes (HXA-057) are appended read-only and re-verified on every browse.
     * HXA-058 adds the 导入/导出 entries: the HXA-044 pipelines driven by the file manager's
     * explicit user actions (pickers / authorized trees) — no chat message, no Provider call, no
     * Agent scope expansion.
     */
    val fileManager: FileManagerService =
        FileManagerService(
            workspaceStore,
            scopeRoots,
            appScopeId,
            safAccess,
            SafImportExportAccess(
                importPipeline = featureFiles.importPipeline,
                exportPipeline = featureFiles.exportPipeline,
                sourceMetadata = featureFiles.metadataReader,
                treeLister = featureFiles.treeLister,
                treeDestination = featureFiles.treeDestination,
                destinationReReader = featureFiles.destinationReReader,
            ),
            // HXA-069: the facade's user-visible status/detail texts are stable string-resource
            // ids, localized against the CHOSEN app language at emit time (via the injected locale resolver).
            strings = { id, args -> strings(id, args) },
        )
}
