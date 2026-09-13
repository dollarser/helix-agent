package com.helix.core.agent

/**
 * Lifecycle of one [TaskItem] in the model's working-memory ledger (HX2-07, research doc
 * section 13). The ledger is the agent's *current execution progress* — distinct from a Goal
 * (the user's long-term objective) and a Plan (the chosen solution). It is what a `todo_write`
 * keeps in the model's head across a long task so it does not forget the plan.
 */
enum class TaskItemState {
    TODO,
    IN_PROGRESS,
    DONE,
    BLOCKED,
}

/**
 * One line of the [TaskLedger]: a named unit of work with a lifecycle [state].
 */
data class TaskItem(
    val id: String,
    val title: String,
    val state: TaskItemState,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID) {
            "task id must be 1..$MAX_ID non-blank characters"
        }
        require(title.isNotBlank() && title.length <= MAX_TITLE) {
            "task title must be 1..$MAX_TITLE non-blank characters"
        }
    }

    companion object {
        const val MAX_ID = 64
        const val MAX_TITLE = 200
    }
}

/**
 * The model's working-memory ledger of current execution progress (HX2-07). A pure, bounded
 * value: the model replaces or updates it as it works, and the UI / context engine read it back
 * to keep long tasks legible (research doc: "Task Ledger = Agent 当前执行进度"). Persistence of
 * the ledger across a Turn/Goal is owned by the surrounding stores, not here.
 */
data class TaskLedger(
    val items: List<TaskItem>,
) {
    init {
        require(items.size <= MAX_ITEMS) { "a ledger holds <= $MAX_ITEMS items" }
        val ids = items.map { it.id }
        require(ids.distinct().size == ids.size) { "task ids must be unique within a ledger" }
    }

    /** True when there is at least one item and every item is [TaskItemState.DONE]. */
    val isComplete: Boolean
        get() = items.isNotEmpty() && items.all { it.state == TaskItemState.DONE }

    /** True when any item is [TaskItemState.BLOCKED]. */
    val isBlocked: Boolean
        get() = items.any { it.state == TaskItemState.BLOCKED }

    val doneCount: Int
        get() = items.count { it.state == TaskItemState.DONE }

    val inProgressCount: Int
        get() = items.count { it.state == TaskItemState.IN_PROGRESS }

    /** Items that are not yet DONE. */
    val openCount: Int
        get() = items.size - doneCount

    fun byId(id: String): TaskItem? = items.firstOrNull { it.id == id }

    /**
     * Returns a ledger with the item [id] moved to [state]. Fails when [id] is absent — a model
     * updating an unknown id is a bug, not a silent no-op. The receiver is unchanged (value
     * semantics).
     */
    fun withState(
        id: String,
        state: TaskItemState,
    ): TaskLedger {
        val index = items.indexOfFirst { it.id == id }
        require(index >= 0) { "task id $id is not in this ledger" }
        val updated = items.mapIndexed { i, item -> if (i == index) item.copy(state = state) else item }
        return TaskLedger(updated)
    }

    companion object {
        const val MAX_ITEMS = 50

        val EMPTY: TaskLedger = TaskLedger(emptyList())
    }
}
