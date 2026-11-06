package com.helix.extensions.mobileuse.automation.backend

internal data class RetainedTaskLocation(
    val id: Int,
    val display: Int,
    val packageName: String,
)

/** null means no prior task; empty means recovery refused, never permission to relaunch. */
internal fun retainedTaskPlan(
    retained: List<RetainedDisplayTask>,
    current: List<RetainedTaskLocation>,
    target: Int,
    packageName: String,
    allowPrimary: Boolean,
    recoverable: Set<Int> = emptySet(),
): List<Int>? {
    val records = retained.filter { it.packageName == packageName }
    if (records.isEmpty()) return null
    val matches =
        records.map { record ->
            current.singleOrNull {
                it.id == record.id &&
                    it.packageName == record.packageName
            }
        }
    val eligible =
        matches.all { task ->
            task != null &&
                when (task.display) {
                    target -> true
                    0 -> allowPrimary || task.id in recoverable
                    else -> false
                }
        }
    return if (!eligible) {
        emptyList()
    } else {
        matches.filterNotNull().map { it.id }
    }
}

/** A lost-display exception belongs to the original task and is consumed before an attempted move. */
internal class RetainedTaskRecovery(
    records: List<RetainedDisplayTask>,
    lostDisplay: Boolean,
) {
    private val pending = if (lostDisplay) records.associateBy { it.id }.toMutableMap() else mutableMapOf()

    fun remaining(
        current: List<RetainedTaskLocation>,
        target: Int,
    ): Set<Int> {
        current
            .filter { it.display == target && pending[it.id]?.packageName == it.packageName }
            .forEach { pending.remove(it.id) }
        return pending.keys.toSet()
    }

    fun dispatched(task: Int) {
        pending.remove(task)
    }
}
