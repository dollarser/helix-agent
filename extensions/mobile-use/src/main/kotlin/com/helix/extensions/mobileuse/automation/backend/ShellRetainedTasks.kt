package com.helix.extensions.mobileuse.automation.backend

/** Recovery is an explicit launch effect, never an observation or an activity restart. */
@android.annotation.SuppressLint("PrivateApi")
internal object ShellRetainedTasks {
    private val type = Class.forName("android.app.IActivityTaskManager")
    private val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)

    private fun tasks(): List<RetainedTaskLocation> {
        val values = type.getMethod("getAllRootTaskInfos").invoke(service) as List<*>
        return values.filterNotNull().mapNotNull { value ->
            val top = value.javaClass.getField("topActivity").get(value) as? android.content.ComponentName
            top?.let {
                RetainedTaskLocation(
                    value.javaClass.getField("taskId").getInt(value),
                    value.javaClass.getField("displayId").getInt(value),
                    it.packageName,
                )
            }
        }
    }

    fun read(display: Int): List<RetainedDisplayTask> =
        tasks().filter { it.display == display }.take(32).map { RetainedDisplayTask(it.id, it.packageName) }

    @Suppress("ReturnCount") // Every refusal is before dispatch; any uncertain move is never relaunched.
    fun launch(
        display: ShellBackgroundDisplay,
        packageName: String,
        allowMoveFromMain: Boolean,
        allowed: () -> Boolean,
    ): String? {
        val current = tasks()
        val recoverable = display.recovery.remaining(current, display.id)
        if (!allowMoveFromMain && primaryTaskRecorded(display.retainedTasks, current, packageName, recoverable)) {
            return "MAIN_DISPLAY_IN_USE"
        }
        val plan =
            retainedTaskPlan(display.retainedTasks, current, display.id, packageName, allowMoveFromMain, recoverable)
                ?: return null
        if (plan.isEmpty() || !allowed()) return "ACTION_NOT_DISPATCHED"
        return move(current.filter { it.id in plan }, display.id, allowed, display.recovery::dispatched)
    }

    private fun primaryTaskRecorded(
        records: List<RetainedDisplayTask>,
        current: List<RetainedTaskLocation>,
        packageName: String,
        recoverable: Set<Int>,
    ): Boolean {
        val ids = records.filter { it.packageName == packageName }.map { it.id }.toSet()
        return current.any { it.display == 0 && it.id in ids && it.id !in recoverable && it.packageName == packageName }
    }

    @Suppress("ReturnCount") // A dispatched prefix is UNKNOWN on cancellation; never start an activity instead.
    private fun move(
        matches: List<RetainedTaskLocation>,
        target: Int,
        allowed: () -> Boolean,
        consume: (Int) -> Unit,
    ): String {
        val plan = matches.map { it.id }
        var dispatched = false
        for (task in matches) {
            if (!allowed()) return if (dispatched) "ACTION_OUTCOME_UNKNOWN" else "ACTION_NOT_DISPATCHED"
            if (task.display != target) {
                consume(task.id)
                dispatched = true
                type
                    .getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(service, task.id, target)
            }
        }
        if (!allowed()) return if (dispatched) "ACTION_OUTCOME_UNKNOWN" else "ACTION_NOT_DISPATCHED"
        type.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType).invoke(service, plan.first())
        val after = tasks()
        return if (allowed() && plan.all { id -> after.singleOrNull { it.id == id }?.display == target }) {
            "SUCCEEDED"
        } else {
            "ACTION_OUTCOME_UNKNOWN"
        }
    }
}
