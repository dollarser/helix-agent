package com.helix.tools.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.helix.core.model.Clock
import com.helix.core.policy.AutomationSessionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AutomationFinderTest {
    private val snapshot =
        AutomationSnapshot(
            packageName = PACKAGE,
            windowId = 2,
            generation = 3,
            createdAt = Instant.EPOCH,
            nodes =
                listOf(
                    snapshotNode("a", "Continue", "Primary action", clickable = true),
                    snapshotNode("b", "Cancel", "Secondary action", clickable = true),
                ),
            truncated = false,
        )

    @Test
    fun exactAndContainsFindReturnOnlyBoundedMatchingNodes() {
        val exact = AutomationFinder.find(snapshot, AutomationFindQuery(text = "Continue"))
        assertEquals(AutomationFindStatus.FOUND, exact.status)
        assertEquals(listOf("a"), exact.nodes.map { it.token })

        val contains =
            AutomationFinder.find(
                snapshot,
                AutomationFindQuery(
                    contentDescription = "action",
                    clickable = true,
                    match = AutomationTextMatch.CONTAINS,
                    maxResults = 1,
                ),
            )
        assertEquals(AutomationFindStatus.FOUND, contains.status)
        assertEquals(1, contains.nodes.size)
    }

    @Test
    fun emptyMalformedAndOversizedQueriesFailClosed() {
        assertEquals(
            AutomationFindStatus.INVALID_QUERY,
            AutomationFinder.find(snapshot, AutomationFindQuery()).status,
        )
        assertEquals(
            AutomationFindStatus.INVALID_QUERY,
            AutomationFinder.find(snapshot, AutomationFindQuery(text = " ")).status,
        )
        assertEquals(
            AutomationFindStatus.INVALID_QUERY,
            AutomationFinder.find(snapshot, AutomationFindQuery(text = "Continue", maxResults = 51)).status,
        )
    }

    companion object {
        private const val PACKAGE = "com.example.fixture"
    }
}

class AutomationNodeActionExecutorTest {
    private val clock = ActionMutableClock(Instant.parse("2026-09-05T03:00:00Z"))
    private var tokenSeed = 0
    private val registry =
        NodeTokenRegistry(clock) {
            tokenSeed += 1
            ByteArray(16) { tokenSeed.toByte() }
        }
    private val executor = AutomationNodeActionExecutor(registry)
    private val session = actionSession()

    @Test
    fun clickRewalksTokenPathRevalidatesFingerprintAndRecyclesNodes() {
        val child = ActionFakeNode(text = "Continue", clickable = true)
        val root = ActionFakeNode(children = listOf(child))
        val token = issue(child, path = listOf(0), generation = 7)

        val result =
            executor.execute(
                root,
                session,
                generation = 7,
                AutomationNodeActionRequest(AutomationNodeAction.CLICK, token),
            )

        assertEquals(AutomationActionStatus.SUCCEEDED, result.status)
        assertEquals(AccessibilityNodeInfo.ACTION_CLICK, child.performedAction)
        assertEquals(1, root.recycleCount)
        assertEquals(1, child.recycleCount)
    }

    @Test
    fun expiredUnknownAndMalformedTokensNeverTouchANode() {
        val child = ActionFakeNode(text = "Continue", clickable = true)
        val token = issue(child, path = emptyList(), generation = 1)
        clock.instant = clock.now().plus(NodeTokenRegistry.TOKEN_TTL)

        assertEquals(
            AutomationActionStatus.TOKEN_EXPIRED,
            executor
                .execute(
                    ActionFakeNode(),
                    session,
                    1,
                    AutomationNodeActionRequest(AutomationNodeAction.CLICK, token),
                ).status,
        )
        assertEquals(
            AutomationActionStatus.TOKEN_UNKNOWN,
            executor
                .execute(
                    ActionFakeNode(),
                    session,
                    1,
                    AutomationNodeActionRequest(AutomationNodeAction.CLICK, "not-a-token"),
                ).status,
        )
    }

    @Test
    fun packageWindowGenerationAndFingerprintChangesRefuseBeforeAction() {
        val original = ActionFakeNode(text = "Continue", clickable = true)

        var token = issue(original, emptyList(), generation = 4)
        assertEquals(
            AutomationActionStatus.TARGET_CHANGED,
            execute(ActionFakeNode(packageName = "org.example.other", clickable = true), token, 4).status,
        )

        token = issue(original, emptyList(), generation = 4)
        assertEquals(
            AutomationActionStatus.TARGET_CHANGED,
            execute(ActionFakeNode(windowId = 99, clickable = true), token, 4).status,
        )

        token = issue(original, emptyList(), generation = 4)
        assertEquals(
            AutomationActionStatus.STALE_TOKEN,
            execute(ActionFakeNode(clickable = true), token, 5).status,
        )

        token = issue(original, emptyList(), generation = 4)
        assertEquals(
            AutomationActionStatus.STALE_TOKEN,
            execute(ActionFakeNode(text = "Changed", clickable = true), token, 4).status,
        )
    }

    @Test
    fun unsupportedCapabilityAndPlatformFailureAreDistinct() {
        var token = issue(ActionFakeNode(text = "Label"), emptyList(), generation = 6)
        assertEquals(
            AutomationActionStatus.ACTION_NOT_SUPPORTED,
            execute(ActionFakeNode(text = "Label"), token, 6).status,
        )

        token = issue(ActionFakeNode(clickable = true), emptyList(), generation = 6)
        assertEquals(
            AutomationActionStatus.ACTION_FAILED,
            execute(ActionFakeNode(clickable = true, performResult = false), token, 6).status,
        )
    }

    @Test
    fun missingAndOversizedSetTextArgumentsFailBeforePlatformAction() {
        val editable = ActionFakeNode(editable = true)
        var token = issue(editable, emptyList(), generation = 6)
        assertEquals(
            AutomationActionStatus.INVALID_ARGUMENT,
            executor
                .execute(
                    ActionFakeNode(editable = true),
                    session,
                    6,
                    AutomationNodeActionRequest(AutomationNodeAction.SET_TEXT, token),
                ).status,
        )

        token = issue(editable, emptyList(), generation = 6)
        assertEquals(
            AutomationActionStatus.INVALID_ARGUMENT,
            executor
                .execute(
                    ActionFakeNode(editable = true),
                    session,
                    6,
                    AutomationNodeActionRequest(
                        AutomationNodeAction.SET_TEXT,
                        token,
                        "x".repeat(2_001),
                    ),
                ).status,
        )
    }

    @Test
    fun sensitiveSemanticsAndDeniedPackagesRefuseBeforePlatformAction() {
        val ordinary = ActionFakeNode(clickable = true)
        var token = issue(ordinary, emptyList(), generation = 6)
        assertEquals(
            AutomationActionStatus.SENSITIVE_UI,
            execute(ActionFakeNode(clickable = true, password = true), token, 6).status,
        )

        token = issue(ordinary, emptyList(), generation = 6)
        assertEquals(
            AutomationActionStatus.SENSITIVE_UI,
            execute(
                ActionFakeNode(clickable = true, accessibilityDataSensitive = true),
                token,
                6,
            ).status,
        )

        token = issue(ordinary, emptyList(), generation = 6)
        val deniedExecutor = AutomationNodeActionExecutor(registry) { it == PACKAGE }
        assertEquals(
            AutomationActionStatus.SENSITIVE_UI,
            deniedExecutor
                .execute(
                    ActionFakeNode(clickable = true),
                    session,
                    6,
                    AutomationNodeActionRequest(AutomationNodeAction.CLICK, token),
                ).status,
        )
    }

    private fun execute(
        root: ActionFakeNode,
        token: String,
        generation: Long,
    ) = executor.execute(
        root,
        session,
        generation,
        AutomationNodeActionRequest(AutomationNodeAction.CLICK, token),
    )

    private fun issue(
        node: ActionFakeNode,
        path: List<Int>,
        generation: Long,
    ): String =
        registry.issue(
            NodeTokenBinding(
                packageName = PACKAGE,
                windowId = WINDOW_ID,
                generation = generation,
                fingerprint = node.observe().fingerprint(PACKAGE, WINDOW_ID, path),
                path = path,
            ),
        )
}

class AutomationWaiterTest {
    private val clock = ActionMutableClock(Instant.parse("2026-09-05T04:00:00Z"))
    private val waiter = AutomationWaiter(clock) { clock.instant = clock.now().plus(it) }

    @Test
    fun waitsWithinBudgetUntilMatchingSnapshotAppears() {
        var calls = 0
        val result =
            waiter.waitFor(
                query = AutomationFindQuery(text = "Ready"),
                timeout = Duration.ofSeconds(2),
            ) {
                calls += 1
                AutomationSnapshotResult(
                    AutomationSnapshotStatus.SUCCESS,
                    actionSnapshot(text = if (calls < 3) "Waiting" else "Ready"),
                )
            }

        assertEquals(AutomationWaitStatus.FOUND, result.status)
        assertEquals(3, calls)
        assertEquals("Ready", result.matches.single().text)
    }

    @Test
    fun timeoutInvalidBudgetAndSnapshotRefusalAreStableResults() {
        assertEquals(
            AutomationWaitStatus.TIMED_OUT,
            waiter
                .waitFor(
                    AutomationFindQuery(text = "Never"),
                    Duration.ofMillis(250),
                    Duration.ofMillis(100),
                ) { AutomationSnapshotResult(AutomationSnapshotStatus.SUCCESS, actionSnapshot("Other")) }
                .status,
        )
        assertEquals(
            AutomationWaitStatus.INVALID_ARGUMENT,
            waiter
                .waitFor(AutomationFindQuery(text = "x"), Duration.ofSeconds(11)) {
                    error("provider must not run")
                }.status,
        )
        assertEquals(
            AutomationWaitStatus.SNAPSHOT_REFUSED,
            waiter
                .waitFor(AutomationFindQuery(text = "x"), Duration.ofSeconds(1)) {
                    AutomationSnapshotResult(AutomationSnapshotStatus.SENSITIVE_UI)
                }.status,
        )
    }
}

private fun actionSession() =
    ActiveAutomationSession(
        id = "session",
        startedAt = Instant.EPOCH,
        scope =
            AutomationSessionScope(
                allowedPackages = setOf(PACKAGE),
                deniedPackages = emptySet(),
                maxActions = 30,
                expiresAt = Instant.parse("2100-01-01T00:00:00Z"),
            ),
    )

private fun snapshotNode(
    token: String,
    text: String,
    description: String,
    clickable: Boolean,
) = AutomationSnapshotNode(
    token = token,
    parentToken = null,
    depth = 0,
    className = "android.widget.Button",
    text = text,
    contentDescription = description,
    viewId = "$PACKAGE:id/$token",
    bounds = AutomationNodeBounds(0, 0, 10, 10),
    clickable = clickable,
    longClickable = false,
    editable = false,
    scrollable = false,
    enabled = true,
)

private fun actionSnapshot(text: String) =
    AutomationSnapshot(
        packageName = PACKAGE,
        windowId = WINDOW_ID,
        generation = 1,
        createdAt = Instant.EPOCH,
        nodes = listOf(snapshotNode("token", text, "description", clickable = false)),
        truncated = false,
    )

private class ActionMutableClock(
    var instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

@Suppress("LongParameterList")
private class ActionFakeNode(
    override val packageName: String? = PACKAGE,
    override val windowId: Int = WINDOW_ID,
    override val className: String? = "android.view.View",
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val viewId: String? = null,
    override val bounds: AutomationNodeBounds = AutomationNodeBounds(0, 0, 10, 10),
    override val clickable: Boolean = false,
    override val longClickable: Boolean = false,
    override val editable: Boolean = false,
    override val scrollable: Boolean = false,
    override val enabled: Boolean = true,
    override val password: Boolean = false,
    override val accessibilityDataSensitive: Boolean = false,
    private val children: List<ActionFakeNode> = emptyList(),
    private val performResult: Boolean = true,
) : SnapshotNode {
    var recycleCount = 0
        private set
    var performedAction: Int? = null
        private set

    override val childCount: Int
        get() = children.size

    override fun childAt(index: Int): SnapshotNode? = children.getOrNull(index)

    override fun performAction(
        action: Int,
        arguments: Bundle?,
    ): Boolean {
        performedAction = action
        return performResult
    }

    override fun recycle() {
        recycleCount += 1
    }
}

private const val PACKAGE = "com.example.fixture"
private const val WINDOW_ID = 42
