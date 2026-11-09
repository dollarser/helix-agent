package com.helix.extensions.mobileuse.automation

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityNodeCapabilitiesDeviceTest {
    @Test fun platformVisibilitySurvivesTheNodeAdapter() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            val observed = AndroidSnapshotNode(node)
            node.isVisibleToUser = false
            assertFalse(observed.visibleToUser)
            node.isVisibleToUser = true
            assertTrue(observed.visibleToUser)
        } finally {
            node.recycle()
        }
    }

    @Test fun delegatedLongClickIsAvailableWithoutTheViewFlagAndDisappearsWhenRemoved() {
        val node = AccessibilityNodeInfo.obtain()
        try {
            val observed = AndroidSnapshotNode(node)
            assertFalse(observed.longClickable)
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            assertFalse(observed.longClickable)
            node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            assertFalse(node.isLongClickable)
            assertTrue(observed.longClickable)
            assertTrue(node.automationLongClickable)
            node.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            assertFalse(observed.longClickable)
            node.isLongClickable = true
            assertTrue(observed.longClickable)
        } finally {
            node.recycle()
        }
    }
}
