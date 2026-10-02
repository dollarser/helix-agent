package com.helix.tools.automation

import com.helix.core.model.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AutomationWaitConditionsTest {
    private var now = Instant.EPOCH
    private val waiter =
        AutomationWaiter(
            object : Clock {
                override fun now() = now
            },
        ) { now = now.plus(it) }

    @Test fun absenceRequiresACompleteSuccessfulObservation() {
        val query = AutomationFindQuery(text = "Loading")
        assertEquals(
            AutomationWaitStatus.ABSENT,
            waiter
                .waitFor(
                    query,
                    Duration.ofSeconds(1),
                    condition = AutomationWaitCondition.ABSENT,
                ) { snapshot("Ready") }
                .status,
        )
        assertEquals(
            AutomationWaitStatus.INCOMPLETE_SNAPSHOT,
            waiter
                .waitFor(
                    query,
                    Duration.ofSeconds(1),
                    condition = AutomationWaitCondition.ABSENT,
                ) { snapshot("Ready", truncated = true) }
                .status,
        )
        assertEquals(
            AutomationWaitStatus.SNAPSHOT_REFUSED,
            waiter
                .waitFor(
                    query,
                    Duration.ofSeconds(1),
                    condition = AutomationWaitCondition.ABSENT,
                ) {
                    AutomationSnapshotResult(AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED)
                }.status,
        )
    }

    @Test fun changingContentDoesNotDependOnRegeneratedNodeTokens() {
        var count = 0
        val result =
            waiter.waitFor(AutomationFindQuery(), Duration.ofSeconds(1), condition = AutomationWaitCondition.CHANGED) {
                count++
                snapshot(if (count < 3) "Loading" else "Ready", token = "token-$count")
            }
        assertEquals(AutomationWaitStatus.CHANGED, result.status)
        assertEquals(3, count)
        assertEquals("Ready", result.matches.single().text)
    }

    @Test fun stableContentIgnoresTokenChurnButNotWindowIdentity() {
        var count = 0
        val result =
            waiter.waitFor(AutomationFindQuery(), Duration.ofSeconds(2), condition = AutomationWaitCondition.STABLE) {
                count++
                snapshot("Ready", token = "token-$count", window = if (count < 3) 1 else 2)
            }
        assertEquals(AutomationWaitStatus.STABLE, result.status)
        assertTrue(count >= 6)
    }

    @Test fun semanticVoidTimesOutInsteadOfProvingAnElementAbsent() {
        val result =
            waiter.waitFor(
                AutomationFindQuery(text = "Loading"),
                Duration.ofMillis(250),
                condition = AutomationWaitCondition.ABSENT,
            ) { AutomationSnapshotResult(AutomationSnapshotStatus.UNSUPPORTED_UI) }
        assertEquals(AutomationWaitStatus.TIMED_OUT, result.status)
    }

    @Test fun cancellationDoesNotPerformFurtherSnapshots() {
        var count = 0
        val result =
            waiter.waitFor(
                AutomationFindQuery(text = "Other"),
                Duration.ofSeconds(2),
                cancelled = { count == 2 },
            ) {
                count++
                snapshot("Ready")
            }
        assertEquals(AutomationWaitStatus.CANCELLED, result.status)
        assertEquals(2, count)
    }

    @Test fun resultLimitDoesNotHideAChangingMatchedNode() {
        var calls = 0
        val result =
            waiter.waitFor(
                AutomationFindQuery(className = "View", maxResults = 1),
                Duration.ofSeconds(1),
                condition = AutomationWaitCondition.CHANGED,
            ) {
                calls++
                val first = requireNotNull(snapshot("unchanged").snapshot)
                val second = first.nodes.single().copy(text = if (calls < 3) "before" else "after")
                AutomationSnapshotResult(AutomationSnapshotStatus.SUCCESS, first.copy(nodes = first.nodes + second))
            }
        assertEquals(AutomationWaitStatus.CHANGED, result.status)
        assertEquals(3, calls)
        assertEquals(1, result.matches.size)
    }

    @Test fun aLateSnapshotCannotTurnTimeoutIntoSuccess() {
        val result =
            waiter.waitFor(AutomationFindQuery(text = "Ready"), Duration.ofSeconds(1)) {
                now = now.plusSeconds(2)
                snapshot("Ready")
            }
        assertEquals(AutomationWaitStatus.TIMED_OUT, result.status)
    }

    private fun snapshot(
        text: String,
        token: String = "token",
        truncated: Boolean = false,
        window: Int = 1,
    ) = AutomationSnapshotResult(
        AutomationSnapshotStatus.SUCCESS,
        AutomationSnapshot(
            "com.example.app",
            window,
            1,
            now,
            listOf(
                AutomationSnapshotNode(
                    token,
                    null,
                    0,
                    "View",
                    text,
                    null,
                    null,
                    AutomationNodeBounds(0, 0, 100, 100),
                    false,
                    false,
                    false,
                    false,
                    true,
                ),
            ),
            truncated,
        ),
    )
}
