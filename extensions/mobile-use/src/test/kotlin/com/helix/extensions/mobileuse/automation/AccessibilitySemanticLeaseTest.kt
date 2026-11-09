package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AccessibilitySemanticLeaseTest {
    private val parent =
        AutomationSnapshotNode(
            token = "parent",
            parentToken = null,
            depth = 0,
            className = "Container",
            text = null,
            contentDescription = null,
            viewId = "list",
            bounds = AutomationNodeBounds(0, 0, 100, 100),
            clickable = true,
            longClickable = false,
            editable = false,
            scrollable = false,
            enabled = true,
        )
    private val child = parent.copy(token = "child", parentToken = "parent", depth = 1, text = "File A")
    private val snapshot = AutomationSnapshot("example", 7, 3, Instant.EPOCH, listOf(parent, child), false)

    @Test fun unchangedCompleteSemanticsCanSurviveContentEventsAndFreshOpaqueTokens() {
        val fresh =
            snapshot.copy(
                generation = 9,
                createdAt = Instant.EPOCH.plusSeconds(10),
                nodes =
                    listOf(
                        parent.copy(token = "new-parent"),
                        child.copy(token = "new-child", parentToken = "new-parent"),
                    ),
            )
        assertTrue(AccessibilitySemanticLease(snapshot, 1).matches(fresh, 1, "child"))
    }

    @Test fun changedChildContextRejectsEvenWhenClickableParentIsIdentical() {
        assertFalse(
            AccessibilitySemanticLease(
                snapshot,
                1,
            ).matches(snapshot.copy(nodes = listOf(parent, child.copy(text = "File B"))), 1, "parent"),
        )
    }

    @Test fun movementCapabilitiesHierarchyAndWindowIdentityCannotBeRenewed() {
        val lease = AccessibilitySemanticLease(snapshot, 1)
        for (changed in listOf(
            snapshot.copy(windowId = 8),
            snapshot.copy(packageName = "other"),
            snapshot.copy(nodes = listOf(parent, child.copy(bounds = AutomationNodeBounds(1, 0, 100, 100)))),
            snapshot.copy(nodes = listOf(parent, child.copy(enabled = false))),
            snapshot.copy(nodes = listOf(parent, child.copy(canImeEnter = true))),
            snapshot.copy(nodes = listOf(parent, child.copy(parentToken = null))),
            snapshot.copy(nodes = listOf(parent)),
        )) {
            assertFalse(lease.matches(changed, 1, "child"))
        }
        assertFalse("Window transition away and back is not a content event", lease.matches(snapshot, 2, "child"))
    }

    @Test fun incompleteProtectedOrUnresolvableTreesNeverRenew() {
        for (unsafe in listOf(
            snapshot.copy(truncated = true),
            snapshot.copy(nodes = emptyList()),
            snapshot.copy(nodes = listOf(parent.copy(redacted = true))),
            snapshot.copy(nodes = listOf(parent, child.copy(parentToken = "missing"))),
        )) {
            assertFalse(AccessibilitySemanticLease(unsafe, 1).matches(unsafe, 1, "child"))
            assertFalse(AccessibilitySemanticLease(snapshot, 1).matches(unsafe, 1, "child"))
        }
    }

    @Test fun unrelatedTextCanChangeButTargetContextAndOverlaysCannot() {
        val button = child.copy(bounds = AutomationNodeBounds(0, 0, 40, 40))
        val comment = child.copy(token = "comment", text = "Comment 1", bounds = AutomationNodeBounds(0, 60, 100, 100))
        val observed = snapshot.copy(nodes = listOf(parent, button, comment))
        val lease = AccessibilitySemanticLease(observed, 1)
        val changed = observed.copy(nodes = listOf(parent, button, comment.copy(text = "Comment 2")))
        assertTrue(lease.matches(changed, 1, "child"))
        assertFalse(lease.matches(changed, 1, "parent"))
        assertFalse(lease.matches(changed, 1, "unknown"))
        assertFalse(lease.matches(changed.copy(nodes = listOf(parent, comment, button)), 1, "child"))
        assertFalse(
            lease.matches(
                changed.copy(nodes = listOf(parent, button, comment.copy(bounds = button.bounds))),
                1,
                "child",
            ),
        )
        assertFalse(
            lease.matches(changed.copy(nodes = listOf(parent.copy(enabled = false), button, comment)), 1, "child"),
        )
    }
}
