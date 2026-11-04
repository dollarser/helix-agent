package com.helix.extensions.mobileuse.automation.backend

import android.os.SystemClock

/** Preserve existing task identities on display removal; never relaunch or force-stop an app. */
@android.annotation.SuppressLint("PrivateApi") // Runs in the existing shell-UID UserService, not the app process.
internal class ShellTaskRetention {
    private data class Task(
        val id: Int,
        val visible: Boolean,
    )

    private val type = Class.forName("android.app.IActivityTaskManager")
    private val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)

    private fun tasks(display: Int): List<Task> {
        val values =
            type
                .getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
                .invoke(service, display) as List<*>
        return values.filterNotNull().map {
            Task(it.javaClass.getField("taskId").getInt(it), it.javaClass.getField("visible").getBoolean(it))
        }
    }

    fun checkpoint(displayId: Int): FocusRecoveryCheckpoint? {
        val primary = tasks(0).firstOrNull { it.visible } ?: return null
        val owned = tasks(displayId).map { it.id }
        return if (owned.size in 1..32 && primary.id !in owned) {
            FocusRecoveryCheckpoint(displayId, primary.id, owned)
        } else {
            null
        }
    }

    fun restore(
        checkpoint: FocusRecoveryCheckpoint,
        allowed: () -> Boolean,
    ): String {
        val current = tasks(0)
        return when {
            !checkpoint.canRestore(current.map { it.id }, current.firstOrNull { it.visible }?.id) -> {
                "MAIN_FOCUS_CHANGED"
            }

            !allowed() -> {
                "AUTHORIZATION_LOST"
            }

            else -> {
                type.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType).invoke(service, checkpoint.primary)
                if (tasks(0).firstOrNull { it.visible }?.id == checkpoint.primary) {
                    "FOCUS_RESTORED"
                } else {
                    "FOCUS_RESTORE_UNCONFIRMED"
                }
            }
        }
    }

    /** The platform moves public-display tasks to primary. Restore focus only if that move took it. */
    @Suppress("ReturnCount") // Report each unconfirmed phase without relaunching or discarding the existing task.
    fun release(
        displayId: Int,
        close: () -> Unit,
    ): String {
        val primary = tasks(0).firstOrNull { it.visible }
        val owned = tasks(displayId).map { it.id }.toSet()
        android.util.Log.i(
            "HelixDisplay",
            "Release $displayId: primaryTask=${primary?.id};ownedTasks=${owned.sorted()}",
        )
        close()
        if (owned.isEmpty()) return "NO_TASKS"
        val deadline = SystemClock.uptimeMillis() + 1500
        var current = tasks(0)
        while (!current.map { it.id }.containsAll(owned) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
            current = tasks(0)
        }
        if (!current.map { it.id }.containsAll(owned)) return "TASK_RETENTION_UNCONFIRMED"
        if (primary != null && current.firstOrNull { it.visible }?.id in owned &&
            current.any { it.id == primary.id }
        ) {
            type.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType).invoke(service, primary.id)
            if (tasks(0).firstOrNull { it.visible }?.id != primary.id) return "FOCUS_RESTORE_UNCONFIRMED"
        }
        return "TASKS_PRESERVED"
    }

    companion object {
        @Suppress("TooGenericExceptionCaught") // Keep the original tool result even on unsupported task introspection.
        fun checkpoint(displayId: Int): FocusRecoveryCheckpoint? =
            try {
                ShellTaskRetention().checkpoint(displayId)
            } catch (failure: Exception) {
                android.util.Log.w("HelixDisplay", "Focus checkpoint unavailable", failure)
                null
            }

        fun restore(
            checkpoint: FocusRecoveryCheckpoint,
            allowed: () -> Boolean,
        ): String = ShellTaskRetention().restore(checkpoint, allowed)

        // Release pixels even when task introspection is unsupported; never claim confirmed task retention.
        @Suppress("TooGenericExceptionCaught")
        fun release(
            displayId: Int,
            close: () -> Unit,
        ) {
            var closed = false
            val release = {
                close()
                closed = true
            }
            val result =
                try {
                    ShellTaskRetention().release(displayId, release)
                } catch (error: Exception) {
                    android.util.Log.w("HelixDisplay", "Task retention not confirmed", error)
                    "TASK_RETENTION_UNCONFIRMED"
                } finally {
                    if (!closed) close()
                }
            android.util.Log.i("HelixDisplay", "Display $displayId cleanup: $result")
        }
    }
}
