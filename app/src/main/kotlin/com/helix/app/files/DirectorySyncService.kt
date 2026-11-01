package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class DirectorySyncRule(
    val id: String,
    val left: SyncRoot,
    val right: SyncRoot,
    val enabled: Boolean = false,
    val status: String = "PAUSED",
    val base: Map<String, SyncNode> = emptyMap(),
    val conflicts: List<String> = emptyList(),
    val updatedAt: Long = 0,
    val copied: Int = 0,
)

/** Explicit user directory rule; never an Agent permission, scope expansion or tool execution. */
internal class DirectorySyncService(
    private val manual: ManualFileOperations,
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val schedule: (String, Boolean) -> Unit,
) {
    private val lock = Any()
    private val active = ConcurrentHashMap<String, AtomicBoolean>()
    private var rules = readRules()
    private val scan = DirectorySyncScan(manual::syncBackend)

    init {
        synchronized(lock) {
            // An abandoned operation is inspected, never automatically replayed.
            rules = rules.map { if (it.status == "RUNNING") it.copy(enabled = false, status = "REVIEW") else it }
            persist()
            rules.forEach { schedule(it.id, it.enabled) }
        }
    }

    fun list(): List<DirectorySyncRule> = synchronized(lock) { rules.toList() }

    fun create(
        left: SyncRoot,
        right: SyncRoot,
    ): DirectorySyncRule {
        require(
            (left.scope == "app" && right.scope.startsWith("saf-")) ||
                (right.scope == "app" && left.scope.startsWith("saf-")),
        ) { "SYNC_SELECT_PRIVATE_AND_SAF" }
        validateSyncRoots(left, right)
        require(manual.syncBackend(left.scope).stat(left.path)?.directory == true) { "SYNC_ROOT_UNAVAILABLE" }
        require(manual.syncBackend(right.scope).stat(right.path)?.directory == true) { "SYNC_ROOT_UNAVAILABLE" }
        val rule = DirectorySyncRule(UUID.randomUUID().toString(), left, right)
        synchronized(lock) {
            require(rules.size < 16) { "SYNC_RULE_LIMIT" }
            require(
                rules.none { existing ->
                    listOf(left, right).any { root ->
                        listOf(existing.left, existing.right).any { syncRootsOverlap(root, it) }
                    }
                },
            ) { "SYNC_ROOT_OVERLAP" }
            val next = rules + rule
            persist(next)
            rules = next
        }
        return rule
    }

    fun enable(
        id: String,
        enabled: Boolean,
    ) {
        synchronized(lock) {
            check(!active.containsKey(id) || !enabled) { "SYNC_BUSY" }
            active[id]?.set(true)
            change(id) { it.copy(enabled = enabled, status = if (enabled) "READY" else "PAUSED") }
            schedule(id, enabled)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            check(!active.containsKey(id)) { "SYNC_BUSY" }
            val next = rules.filterNot { it.id == id }
            persist(next)
            rules = next
            schedule(id, false)
        }
    }

    /** Each copied file has its own existing write-ahead transfer record. Completed writes are not rolled back. */
    @Suppress("TooGenericExceptionCaught") // Durable REVIEW/PAUSED records are failures, not successful empty syncs.
    fun run(
        id: String,
        stopped: () -> Boolean = { false },
        automatic: Boolean = false,
    ) {
        val cancelled = AtomicBoolean()
        val rule =
            synchronized(lock) {
                val found = rules.singleOrNull { it.id == id } ?: return
                if (automatic && !found.enabled) return
                check(active.size < 2 && active.putIfAbsent(id, cancelled) == null) { "SYNC_BUSY" }
                try {
                    change(id) { it.copy(status = "RUNNING", conflicts = emptyList(), copied = 0) }
                } catch (failure: Exception) {
                    active.remove(id)
                    throw failure
                }
                found
            }
        val cancel = { cancelled.get() || stopped() }
        try {
            check(manual.pendingTransfers().isEmpty()) { "SYNC_RECOVERY_REQUIRED" }
            execute(rule, cancel)
        } catch (_: CancellationException) {
            synchronized(lock) {
                change(id) { it.copy(enabled = false, status = "PAUSED") }
                schedule(id, false)
            }
        } catch (_: Exception) {
            synchronized(lock) {
                change(id) { it.copy(enabled = false, status = "REVIEW") }
                schedule(id, false)
            }
        } finally {
            active.remove(id)
        }
    }

    private fun execute(
        rule: DirectorySyncRule,
        cancel: () -> Boolean,
    ) {
        val left = scan.scan(rule.left, cancel)
        val right = scan.scan(rule.right, cancel)
        val plan = DirectorySyncPlan.build(rule.base, left, right)
        if (plan.conflicts.isNotEmpty()) {
            synchronized(lock) {
                change(rule.id) { it.copy(enabled = false, status = "CONFLICT", conflicts = plan.conflicts) }
                schedule(rule.id, false)
            }
            return
        }
        var count = 0
        for (copy in plan.copies) {
            if (cancel()) throw CancellationException("SYNC_CANCELLED")
            copy(rule, copy, cancel)
            count++
            synchronized(lock) {
                change(rule.id) { it.copy(base = it.base + (copy.relative to copy.source), copied = count) }
            }
        }
        val after = scan.scan(rule.left, cancel)
        check(after == scan.scan(rule.right, cancel)) { "SYNC_CHANGED_DURING_RUN" }
        if (cancel()) throw CancellationException("SYNC_CANCELLED")
        synchronized(lock) {
            change(rule.id) { it.copy(base = after, status = "COMPLETE", updatedAt = System.currentTimeMillis()) }
        }
    }

    private fun copy(
        rule: DirectorySyncRule,
        copy: SyncCopy,
        cancel: () -> Boolean,
    ) {
        val source = if (copy.fromLeft) rule.left else rule.right
        val target = if (copy.fromLeft) rule.right else rule.left
        if (copy.source.directory) {
            require(manual.syncBackend(source.scope).stat(source.child(copy.relative))?.directory == true)
            require(!manual.exists(target.scope, target.child(copy.relative)))
            val path = FileScopePath(target.scope, target.child(copy.relative))
            manual.mkdir(target.scope, path.parent.relativePath, path.name)
        } else {
            manual.transfer(
                source.scope,
                source.child(copy.relative),
                target.scope,
                target.child(copy.relative),
                move = false,
                overwrite = copy.target != null,
                cancelled = cancel,
                expected = TransferExpectation(copy.source.hash, copy.target?.hash ?: ""),
            )
        }
    }

    private fun change(
        id: String,
        update: (DirectorySyncRule) -> DirectorySyncRule,
    ) {
        check(rules.any { it.id == id }) { "SYNC_RULE_MISSING" }
        val next = rules.map { if (it.id == id) update(it) else it }
        persist(next)
        rules = next
    }

    private fun persist(next: List<DirectorySyncRule> = rules) {
        val json = DirectorySyncCodec.encode(next)
        require(json.toByteArray().size <= 8 * 1024 * 1024) { "SYNC_STATE_LIMIT" }
        save(json)
    }

    private fun readRules(): List<DirectorySyncRule> {
        val raw = load() ?: return emptyList()
        require(raw.length <= 8 * 1024 * 1024) { "SYNC_STATE_LIMIT" }
        return DirectorySyncCodec.decode(raw).also { all ->
            require(all.size <= 16 && all.map { it.id }.distinct().size == all.size)
            all.forEach { rule ->
                require(UUID.fromString(rule.id).toString() == rule.id && rule.base.size <= 4096)
                validateSyncRoots(rule.left, rule.right)
                rule.base.keys.forEach { require(FileScopePath("app", it).relativePath == it && it.isNotEmpty()) }
            }
        }
    }
}

private fun validateSyncRoots(
    left: SyncRoot,
    right: SyncRoot,
) {
    require(!syncRootsOverlap(left, right)) { "SYNC_ROOT_OVERLAP" }
    require(listOf(left, right).none { it.scope == FileManagerService.ROOT_SCOPE_ID }) { "SYNC_ROOT_UNSUPPORTED" }
}

private fun syncRootsOverlap(
    a: SyncRoot,
    b: SyncRoot,
): Boolean =
    a.scope == b.scope && (a.path == b.path || a.path.startsWith("${b.path}/") || b.path.startsWith("${a.path}/"))
