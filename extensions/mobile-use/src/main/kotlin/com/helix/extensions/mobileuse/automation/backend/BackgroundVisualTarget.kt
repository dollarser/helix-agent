package com.helix.extensions.mobileuse.automation.backend

import android.content.ComponentName
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds

internal data class VisualWindow(
    val id: Int,
    val focused: Boolean,
    val bounds: AutomationNodeBounds,
)

internal data class VisualTask(
    val id: Int,
    val displayId: Int,
    val packageName: String,
)

internal data class VisualDisplay(
    val id: Int,
    val session: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val independent: Boolean,
)

/** A tree-blind target needs a unique visible task on the original owned display. */
@Suppress("ReturnCount") // Missing scope or task identity must refuse before issuing a visual target.
internal fun visualTarget(
    display: VisualDisplay,
    wholeDisplay: Boolean,
    windows: List<VisualWindow>,
    tasks: List<VisualTask>,
): AutomationDisplayTarget? {
    if (!wholeDisplay || display.id <= 0 || display.session.isBlank()) return null
    val task = tasks.singleOrNull { it.displayId == display.id } ?: return null
    if (task.id < 0 || task.packageName.isBlank()) return null
    val window = windows.singleOrNull { it.focused } ?: windows.singleOrNull()
    if (window != null && !window.valid()) return null
    return AutomationDisplayTarget(
        task.packageName,
        window?.id ?: -1,
        display.id,
        display.width,
        display.height,
        display.rotation,
        window?.bounds ?: AutomationNodeBounds(0, 0, display.width, display.height),
        displaySession = display.session,
        independentFocus = display.independent,
        semanticAvailable = false,
        taskId = task.id,
    )
}

/** Same privileged ActivityTaskManager facts already used for task retention; no guessed package. */
@android.annotation.SuppressLint("PrivateApi")
internal fun visibleDisplayTasks(displayId: Int): List<VisualTask> {
    val type = Class.forName("android.app.IActivityTaskManager")
    val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    val tasks =
        type
            .getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
            .invoke(service, displayId) as List<*>
    return tasks.filterNotNull().filter { it.javaClass.getField("visible").getBoolean(it) }.map {
        VisualTask(
            it.javaClass.getField("taskId").getInt(it),
            it.javaClass.getField("displayId").getInt(it),
            (it.javaClass.getField("topActivity").get(it) as? ComponentName)?.packageName.orEmpty(),
        )
    }
}

private fun VisualWindow.valid() = id >= 0 && bounds.right > bounds.left && bounds.bottom > bounds.top
