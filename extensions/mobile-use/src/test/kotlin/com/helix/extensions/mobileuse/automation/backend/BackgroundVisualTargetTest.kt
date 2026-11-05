package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.sameWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundVisualTargetTest {
    private val display = VisualDisplay(7, "owned-generation", 720, 1280, 0, true)
    private val window = VisualWindow(10, true, AutomationNodeBounds(0, 0, 720, 1280))
    private val task = VisualTask(42, 7, "com.example.app")

    @Test fun treeBlindWindowRetainsVisualIdentityWithoutClaimingSemanticSupport() {
        val target = requireNotNull(visualTarget(display, true, listOf(window), listOf(task)))
        assertFalse(target.semanticAvailable)
        assertEquals(42, target.taskId)
        assertEquals("com.example.app", target.packageName)
        assertFalse(target.sameWindow(target.copy(taskId = 43)))
        assertFalse(target.sameWindow(target.copy(displaySession = "other-generation")))
    }

    @Test fun restrictedScopeMissingWindowWrongDisplayOrAmbiguousTasksStayUnavailable() {
        assertNull(visualTarget(display, false, listOf(window), listOf(task)))
        assertNull(visualTarget(display, true, emptyList(), emptyList()))
        assertEquals(-1, requireNotNull(visualTarget(display, true, emptyList(), listOf(task))).windowId)
        assertNull(visualTarget(display, true, listOf(window), listOf(task.copy(displayId = 0))))
        assertNull(visualTarget(display.copy(id = 0), true, listOf(window), listOf(task)))
        assertNull(visualTarget(display, true, listOf(window), listOf(task, task.copy(id = 43))))
        assertEquals(
            -1,
            requireNotNull(visualTarget(display, true, listOf(window, window.copy(id = 11)), listOf(task))).windowId,
        )
    }

    @Test fun invalidWindowOrUnownedDisplayCannotMintAFrameTarget() {
        assertNull(visualTarget(display.copy(session = ""), true, listOf(window), listOf(task)))
        assertNull(visualTarget(display, true, listOf(window.copy(id = -1)), listOf(task)))
        assertNull(
            visualTarget(display, true, listOf(window.copy(bounds = window.bounds.copy(right = 0))), listOf(task)),
        )
        assertNull(visualTarget(display, true, listOf(window), listOf(task.copy(packageName = ""))))
    }
}
