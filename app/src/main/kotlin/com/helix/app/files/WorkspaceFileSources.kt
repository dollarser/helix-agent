package com.helix.app.files

import com.helix.core.storage.HelixStorage

/** IO-only projection. Eligibility is advisory; cleanup rechecks references under admission. */
internal fun workspaceFileSources(
    storage: HelixStorage,
    writable: (String) -> Boolean,
): List<FileSource> =
    storage.workspaces.list().filter { it.availability != "DELETED" }.map { resource ->
        FileSource(
            resource.id,
            resource.ownerSessionId?.let { storage.sessions.find(it)?.title } ?: resource.id,
            FileSourceKind.WORKSPACE,
            supportsMutation = resource.availability == "READY" && writable(resource.id),
            workspaceBackend = resource.backend,
            available = resource.availability == "READY",
            cleanupEligible =
                resource.ownership == "MANAGED" &&
                    resource.availability in
                    setOf("READY", "CLEANUP_FENCED", "CLEANUP_QUARANTINED", "CLEANUP_PURGING") &&
                    !storage.workspaces.retentionReferences(resource.id).retained,
        )
    }
