package com.helix.core.storage.repository

import com.helix.core.storage.dao.WorkspaceDao
import com.helix.core.storage.entity.SessionWorkspaceEntity
import com.helix.core.storage.entity.WorkspaceEntity
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceDirectoryIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Registry and binding facts. Binding never changes permission configuration or deletes files. */
@Suppress("TooManyFunctions") // Cohesive resource registry and binding transaction facade.
class WorkspaceRepository(
    private val dao: WorkspaceDao,
    private val managedRoot: Path,
    private val transaction: (() -> Unit) -> Unit = { it() },
    private val directoryIdentity: (Path) -> String = WorkspaceDirectoryIdentity::witness,
) {
    fun directoryWitness(path: Path): String = directoryIdentity(path)

    fun find(id: String): WorkspaceEntity? = dao.find(id)

    fun list(): List<WorkspaceEntity> = dao.list()

    fun binding(sessionId: String): SessionWorkspaceEntity? = dao.binding(sessionId)

    fun references(workspaceId: String): Int = dao.references(workspaceId)

    fun retentionReferences(workspaceId: String): com.helix.core.storage.entity.WorkspaceReferences {
        requireNotNull(dao.find(workspaceId)) { "Unknown workspace" }
        return dao.retentionReferences(workspaceId, "scope:$workspaceId:")
    }

    fun recordRequest(
        modelCallId: String,
        binding: SessionWorkspaceEntity,
    ) = transaction {
        require(dao.find(binding.workspaceId)?.availability == "READY") { "Workspace is unavailable" }
        dao.recordRequest(
            com.helix.core.storage.entity.ModelCallWorkspaceEntity(
                modelCallId,
                binding.workspaceId,
                binding.relativePath,
                binding.revision,
            ),
        )
    }

    fun requestBinding(modelCallId: String): com.helix.core.storage.entity.ModelCallWorkspaceEntity? =
        dao.requestBinding(modelCallId)

    /**
     * READY is committed only after exclusive creation and identity capture. If a crash leaves a
     * directory without a witness, retain it and fail closed: do not adopt or delete unknown files.
     * An enclosing Room rollback can leave an orphan directory; it is retained, never auto-purged.
     */
    @Synchronized
    fun defaultDirectory(
        sessionId: String,
        now: Long,
    ): String {
        var workspace = dao.ownedBy(sessionId) ?: reserve(sessionId, now)
        val interrupted = workspace.availability == "CREATING" && Files.exists(managedRoot.resolve(workspace.id))
        if (interrupted || workspace.availability == "UNAVAILABLE") {
            // Never adopt an unwitnessed directory. Preserve its bytes and reserve a fresh identity.
            check(dao.retire(workspace.id) == 1)
            workspace = reserve(sessionId, now)
        }
        if (workspace.availability == "CREATING") {
            Files.createDirectories(managedRoot)
            val root = managedRoot.resolve(workspace.id)
            Files.createDirectory(root)
            val witness = directoryIdentity(root)
            check(dao.updateAvailability(workspace.id, "READY", witness) == 1)
        }
        managedDirectory(workspace.id)
        return FileScopePath(workspace.id, "").toModelReference()
    }

    private fun reserve(
        sessionId: String,
        now: Long,
    ): WorkspaceEntity {
        val id = "ws-${UUID.randomUUID()}"
        val entity = WorkspaceEntity(id, "PATH", id, "managed:$id", "MANAGED", "CREATING", null, null, sessionId, now)
        dao.insert(entity)
        return entity
    }

    /** Called on every scope resolution, including queued calls, after the usual permission gates. */
    fun managedDirectory(id: String): Path {
        val workspace = requireNotNull(dao.find(id)) { "Unknown workspace" }
        require(workspace.ownership == "MANAGED" && workspace.availability == "READY") { "Workspace not ready" }
        require(workspace.locator == id && id.matches(Regex("ws-[a-f0-9-]{36}")))
        val root = managedRoot.resolve(id)
        val actual = runCatching { directoryIdentity(root) }.getOrNull()
        if (actual == null || actual != workspace.witness) {
            markUnavailable(id)
            error("Workspace identity unavailable; explicit rebind required")
        }
        return root
    }

    /** Caller supplies verified backend identity, not model-authored metadata. */
    @Synchronized
    fun register(
        backend: String,
        locator: String,
        witness: String,
        now: Long,
        identityLocator: String = locator,
    ): WorkspaceEntity {
        require(backend in setOf("PATH", "SAF") && locator.isNotBlank() && witness.isNotBlank())
        val identity = "$backend:$identityLocator:$witness"
        dao.byIdentity(identity)?.let {
            if (it.availability == "READY") return it
            require(it.availability == "UNAVAILABLE") { "Workspace lifecycle must be resolved before selection" }
            // register is reached only from an explicit user selection, never request execution.
            check(dao.retire(it.id) == 1)
        }
        val entity =
            WorkspaceEntity(
                "ws-${UUID.randomUUID()}",
                backend,
                locator,
                identity,
                "EXTERNAL",
                "READY",
                witness,
                null,
                null,
                now,
            )
        dao.insert(entity)
        return entity
    }

    fun markUnavailable(id: String) {
        requireNotNull(dao.find(id))
        // A late read failure must never overwrite a durable cleanup fence or purge phase.
        dao.compareAvailability(id, "READY", "UNAVAILABLE")
    }

    /** Must be called in the same Room transaction as the session directoryRef projection. */
    fun bind(
        sessionId: String,
        directoryRef: String,
    ) = transaction {
        val ref = FileScopePath.fromModelReference(directoryRef)
        require(dao.find(ref.scopeId)?.availability == "READY") { "Workspace must be ready before binding" }
        val previous = dao.binding(sessionId)
        if (previous?.workspaceId == ref.scopeId && previous.relativePath == ref.relativePath) return@transaction
        dao.bind(
            SessionWorkspaceEntity(
                sessionId,
                ref.scopeId,
                ref.relativePath,
                Math.addExact(previous?.revision ?: 0L, 1L),
            ),
        )
    }
}
