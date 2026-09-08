@file:Suppress("MaxLineLength")

package com.helix.spikes.orchestration

import java.security.MessageDigest

/** HXA-105 experiment only. This module has no dependency on the app or production Tool Registry. */
object SpikeLimits {
    const val MAX_DEPTH = 1
    const val MAX_CONCURRENT = 2
    const val MAX_CHILDREN_PER_PARENT = 4
    const val MAX_SNAPSHOT_BYTES = 32 * 1024
    const val MAX_RESULT_BYTES = 16 * 1024
    const val MAX_WORKFLOW_NODES = 32
}

enum class SpikeRisk { L0, L1, L2, L3 }

enum class SpikeOperation { READ_ONLY, MUTATION, EXTERNAL_EFFECT }

enum class ChildState { SPAWNED, RUNNING, COMPLETED, CANCELLED, NEEDS_REVIEW }

data class ReadOnlyTool(
    val name: String,
    val operation: SpikeOperation,
    val dynamicRisk: SpikeRisk,
)

data class ParentBudget(
    val maxModelCalls: Int,
    val maxTokens: Long,
    val maxToolCalls: Int,
    val maxWallMillis: Long,
) {
    init {
        require(maxModelCalls >= 0 && maxTokens >= 0 && maxToolCalls >= 0 && maxWallMillis >= 0)
    }
}

data class BudgetUsage(
    val modelCalls: Int = 0,
    val tokens: Long = 0,
    val toolCalls: Int = 0,
    val wallMillis: Long = 0,
) {
    init {
        require(modelCalls >= 0 && tokens >= 0 && toolCalls >= 0 && wallMillis >= 0)
    }

    operator fun plus(other: BudgetUsage) =
        BudgetUsage(
            Math.addExact(modelCalls, other.modelCalls),
            Math.addExact(tokens, other.tokens),
            Math.addExact(toolCalls, other.toolCalls),
            Math.addExact(wallMillis, other.wallMillis),
        )
}

data class ChildCompletion(
    val childId: String,
    val sequence: Int,
    val source: String,
    val trust: String,
    val summary: String,
    val evidenceRefs: List<String>,
    val usage: BudgetUsage,
) {
    val hash: String =
        sha256(
            listOf(childId, sequence, source, trust, summary, evidenceRefs.joinToString("\n")).joinToString("\u0000"),
        )
}

data class ChildRecord(
    val childId: String,
    val sequence: Int,
    val depth: Int,
    val taskHash: String,
    val snapshotHash: String,
    val state: ChildState,
    val usage: BudgetUsage = BudgetUsage(),
    val completion: ChildCompletion? = null,
)

interface ChildJournal {
    fun load(parentId: String): List<ChildRecord>

    fun replace(
        parentId: String,
        record: ChildRecord,
    )
}

class InMemoryChildJournal : ChildJournal {
    private val records = linkedMapOf<String, LinkedHashMap<String, ChildRecord>>()

    override fun load(parentId: String): List<ChildRecord> = records[parentId]?.values?.toList().orEmpty()

    override fun replace(
        parentId: String,
        record: ChildRecord,
    ) {
        records.getOrPut(parentId, ::linkedMapOf)[record.childId] = record
    }
}

/** Admission/recovery model used to falsify ADR-0009 before any product integration. */
class BoundedChildCoordinator(
    private val parentId: String,
    private val budget: ParentBudget,
    private val journal: ChildJournal,
) {
    fun spawn(
        childId: String,
        depth: Int,
        task: String,
        snapshot: ByteArray,
    ): ChildRecord =
        synchronized(journal) {
            require(depth == SpikeLimits.MAX_DEPTH) { "child depth must be exactly 1" }
            require(snapshot.size <= SpikeLimits.MAX_SNAPSHOT_BYTES) { "snapshot too large" }
            val current = journal.load(parentId)
            require(current.none { it.childId == childId }) { "duplicate child id" }
            require(current.size < SpikeLimits.MAX_CHILDREN_PER_PARENT) { "parent child limit reached" }
            require(
                current.count { it.state == ChildState.RUNNING || it.state == ChildState.SPAWNED } <
                    SpikeLimits.MAX_CONCURRENT,
            ) {
                "child concurrency limit reached"
            }
            val used = current.fold(BudgetUsage()) { acc, record -> acc + record.usage }
            requireWithinBudget(used)
            require(
                used.modelCalls < budget.maxModelCalls &&
                    used.tokens < budget.maxTokens &&
                    used.toolCalls < budget.maxToolCalls &&
                    used.wallMillis < budget.maxWallMillis,
            ) { "parent budget exhausted" }
            return ChildRecord(childId, current.size, depth, sha256(task), sha256(snapshot), ChildState.SPAWNED).also {
                journal.replace(parentId, it)
            }
        }

    fun start(childId: String): ChildRecord = transition(childId, ChildState.SPAWNED, ChildState.RUNNING)

    fun complete(completion: ChildCompletion): ChildRecord =
        synchronized(journal) {
            require(completion.trust == "untrusted") { "child completion cannot promote its trust" }
            require(completion.summary.toByteArray().size <= SpikeLimits.MAX_RESULT_BYTES) { "result too large" }
            val record = requireRecord(completion.childId)
            require(record.state == ChildState.RUNNING) { "only a running child can complete" }
            require(record.sequence == completion.sequence) { "completion sequence mismatch" }
            val total =
                journal.load(parentId).filterNot { it.childId == record.childId }.fold(completion.usage) { acc, item ->
                    acc +
                        item.usage
                }
            requireWithinBudget(total)
            return record.copy(state = ChildState.COMPLETED, usage = completion.usage, completion = completion).also {
                journal.replace(parentId, it)
            }
        }

    fun cancel(childId: String): ChildRecord =
        synchronized(journal) {
            val record = requireRecord(childId)
            require(record.state == ChildState.SPAWNED || record.state == ChildState.RUNNING) { "child is terminal" }
            return record.copy(state = ChildState.CANCELLED).also { journal.replace(parentId, it) }
        }

    /** A process restart never re-spawns; unresolved running work becomes reviewable. */
    fun recover(): List<ChildRecord> =
        synchronized(journal) {
            journal.load(parentId).map { record ->
                if (record.state == ChildState.RUNNING) {
                    record.copy(state = ChildState.NEEDS_REVIEW).also { journal.replace(parentId, it) }
                } else {
                    record
                }
            }
        }

    fun mergeCompleted(): List<ChildCompletion> =
        synchronized(journal) {
            journal.load(parentId).sortedBy { it.sequence }.mapNotNull { it.completion }
        }

    fun admitTools(tools: List<ReadOnlyTool>): List<ReadOnlyTool> =
        tools.onEach {
            require(it.operation == SpikeOperation.READ_ONLY && it.dynamicRisk <= SpikeRisk.L1) {
                "child tool surface must be read-only and <= L1"
            }
        }

    private fun transition(
        childId: String,
        from: ChildState,
        to: ChildState,
    ): ChildRecord =
        synchronized(journal) {
            val record = requireRecord(childId)
            require(record.state == from) { "illegal child transition" }
            return record.copy(state = to).also { journal.replace(parentId, it) }
        }

    private fun requireRecord(childId: String): ChildRecord =
        requireNotNull(journal.load(parentId).singleOrNull { it.childId == childId }) { "unknown child" }

    private fun requireWithinBudget(usage: BudgetUsage) {
        require(usage.modelCalls <= budget.maxModelCalls)
        require(usage.tokens <= budget.maxTokens)
        require(usage.toolCalls <= budget.maxToolCalls)
        require(usage.wallMillis <= budget.maxWallMillis)
    }
}

enum class WorkflowNodeType { TOOL, READ_ONLY_DELEGATE, BARRIER, CONDITION, VERIFIER }

data class WorkflowNode(
    val id: String,
    val type: WorkflowNodeType,
    val dependencies: List<String> = emptyList(),
)

object WorkflowValidator {
    fun validate(nodes: List<WorkflowNode>) {
        require(nodes.size in 1..SpikeLimits.MAX_WORKFLOW_NODES) { "workflow node limit" }
        require(nodes.map { it.id }.toSet().size == nodes.size) { "duplicate workflow node" }
        val byId = nodes.associateBy { it.id }
        nodes.forEach { node -> require(node.dependencies.all(byId::containsKey)) { "unknown dependency" } }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String) {
            require(visiting.add(id)) { "workflow cycle" }
            if (visited.add(id)) byId.getValue(id).dependencies.forEach(::visit)
            visiting.remove(id)
        }
        nodes.forEach { visit(it.id) }
    }
}

private fun sha256(value: String): String = sha256(value.toByteArray())

private fun sha256(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
