package com.helix.tools.automation

import com.helix.core.model.Clock
import com.helix.core.policy.AutomationSessionScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationSnapshotEngineTest {
    private val clock = SnapshotMutableClock(Instant.parse("2026-09-05T01:00:00Z"))
    private var tokenSeed = 0
    private val registry =
        NodeTokenRegistry(clock) {
            tokenSeed += 1
            ByteArray(16) { tokenSeed.toByte() }
        }
    private val engine = AutomationSnapshotEngine(registry, clock)
    private val session = activeSession(setOf(PACKAGE))

    @Test
    @Suppress("LongMethod")
    fun capturesBoundedTreeAndBindsOpaqueTokensToNodeIdentity() {
        val button =
            FakeSnapshotNode(
                packageName = PACKAGE,
                windowId = 7,
                className = "android.widget.Button",
                text = "Continue",
                viewId = "$PACKAGE:id/continue_button",
                clickable = true,
            )
        val root = FakeSnapshotNode(packageName = PACKAGE, windowId = 7, children = listOf(button))

        val result = engine.capture(root, session, generation = 11)

        assertEquals(AutomationSnapshotStatus.SUCCESS, result.status)
        val snapshot = result.snapshot!!
        assertEquals(PACKAGE, snapshot.packageName)
        assertEquals(7, snapshot.windowId)
        assertEquals(11, snapshot.generation)
        assertEquals(2, snapshot.nodes.size)
        assertFalse(snapshot.truncated)
        assertNull(snapshot.nodes[0].parentToken)
        assertEquals(snapshot.nodes[0].token, snapshot.nodes[1].parentToken)
        assertTrue(snapshot.nodes.all { it.token.length == 32 })

        val binding =
            NodeTokenBinding(
                packageName = PACKAGE,
                windowId = 7,
                generation = 11,
                fingerprint =
                    nodeFingerprint(
                        listOf(
                            PACKAGE,
                            "7",
                            "0",
                            "android.widget.Button",
                            "Continue",
                            null,
                            "$PACKAGE:id/continue_button",
                            AutomationNodeBounds(0, 0, 100, 100).toString(),
                            "true",
                            "false",
                            "false",
                            "false",
                            "true",
                        ),
                    ),
                path = listOf(0),
            )
        assertEquals(
            NodeTokenResolutionStatus.VALID,
            registry
                .resolve(
                    snapshot.nodes[1].token,
                    binding.packageName,
                    binding.windowId,
                    binding.generation,
                    binding.fingerprint,
                ).status,
        )
        assertEquals(1, root.recycleCount)
        assertEquals(1, button.recycleCount)
    }

    @Test
    fun truncatesNodeDepthFieldAndTotalTextBudgetsWithoutClaimingCompleteness() {
        val children =
            List(AutomationSnapshotEngine.MAX_NODES + 10) { index ->
                FakeSnapshotNode(
                    packageName = PACKAGE,
                    windowId = 3,
                    text = "node-$index-${"x".repeat(400)}",
                )
            }
        val root = FakeSnapshotNode(packageName = PACKAGE, windowId = 3, children = children)

        val snapshot = engine.capture(root, session, generation = 1).snapshot!!

        assertEquals(AutomationSnapshotEngine.MAX_NODES, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.nodes.filter { it.text != null }.all { it.text!!.length <= 256 })
        assertTrue(snapshot.nodes.sumOf { it.text?.length ?: 0 } <= 16_384)
        assertEquals(1, root.recycleCount)
        assertTrue(children.take(AutomationSnapshotEngine.MAX_NODES - 1).all { it.recycleCount == 1 })
        assertTrue(children.drop(AutomationSnapshotEngine.MAX_NODES - 1).all { it.recycleCount == 0 })
    }

    @Test
    fun truncatesBeyondDepthBudgetWithoutAcquiringDeeperNodes() {
        val nodes = mutableListOf<FakeSnapshotNode>()
        var child: FakeSnapshotNode? = null
        repeat(AutomationSnapshotEngine.MAX_DEPTH + 3) { depth ->
            val node =
                FakeSnapshotNode(
                    packageName = PACKAGE,
                    windowId = 31,
                    text = "depth-$depth",
                    children = listOfNotNull(child),
                )
            nodes += node
            child = node
        }

        val snapshot = engine.capture(checkNotNull(child), session, generation = 2).snapshot!!

        assertEquals(AutomationSnapshotEngine.MAX_DEPTH + 1, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
        assertTrue(nodes.take(2).all { it.recycleCount == 0 })
        assertTrue(nodes.drop(2).all { it.recycleCount == 1 })
    }

    @Test
    fun refusesSensitivePasswordTreeAndRecyclesEveryAcquiredNode() {
        val password =
            FakeSnapshotNode(
                packageName = PACKAGE,
                windowId = 4,
                text = "secret",
                editable = true,
                password = true,
            )
        val root = FakeSnapshotNode(packageName = PACKAGE, windowId = 4, children = listOf(password))

        val result = engine.capture(root, session, generation = 2)

        assertEquals(AutomationSnapshotStatus.SENSITIVE_UI, result.status)
        assertNull(result.snapshot)
        assertEquals(1, root.recycleCount)
        assertEquals(1, password.recycleCount)
    }

    @Test
    fun refusesFixedAndCategoricallySensitivePackagesBeforeReadingChildren() {
        for (
        packageName in
        listOf(
            "com.android.settings",
            "com.example.mobile.banking",
            "com.example.passwordmanager",
            "com.example.authenticator",
        )
        ) {
            val child = FakeSnapshotNode(packageName = packageName, windowId = 5, text = "Setting")
            val root =
                FakeSnapshotNode(
                    packageName = packageName,
                    windowId = 5,
                    children = listOf(child),
                )

            val result =
                engine.capture(
                    root,
                    activeSession(setOf(packageName)),
                    generation = 3,
                )

            assertEquals(packageName, AutomationSnapshotStatus.SENSITIVE_UI, result.status)
            assertEquals(packageName, 1, root.recycleCount)
            assertEquals(packageName, 0, child.recycleCount)
        }
    }

    @Test
    fun nullRootAndCustomSemanticVoidAreExplicitlyUnsupported() {
        assertEquals(
            AutomationSnapshotStatus.UNSUPPORTED_UI,
            engine.capture(null, session, generation = 4).status,
        )
        val root =
            FakeSnapshotNode(
                packageName = PACKAGE,
                windowId = 6,
                viewId = "android:id/content",
            )

        val result = engine.capture(root, session, generation = 4)

        assertEquals(AutomationSnapshotStatus.UNSUPPORTED_UI, result.status)
        assertNull(result.snapshot)
        assertEquals(1, root.recycleCount)
    }

    @Test
    fun packageWindowChangesAndTraversalFailureFailClosedWithRecycling() {
        val changedChild = FakeSnapshotNode(packageName = "org.example.other", windowId = 8, text = "Other")
        val changedRoot = FakeSnapshotNode(packageName = PACKAGE, windowId = 8, children = listOf(changedChild))
        assertEquals(
            AutomationSnapshotStatus.TARGET_CHANGED,
            engine.capture(changedRoot, session, generation = 5).status,
        )
        assertEquals(1, changedRoot.recycleCount)
        assertEquals(1, changedChild.recycleCount)

        val brokenRoot =
            FakeSnapshotNode(
                packageName = PACKAGE,
                windowId = 9,
                text = "Readable",
                throwOnChildRead = true,
                children = listOf(FakeSnapshotNode(packageName = PACKAGE, windowId = 9)),
            )
        assertEquals(
            AutomationSnapshotStatus.UNSUPPORTED_UI,
            engine.capture(brokenRoot, session, generation = 6).status,
        )
        assertEquals(1, brokenRoot.recycleCount)
    }

    private fun activeSession(packages: Set<String>) =
        ActiveAutomationSession(
            id = "session",
            startedAt = clock.now(),
            scope =
                AutomationSessionScope(
                    allowedPackages = packages,
                    deniedPackages = emptySet(),
                    maxActions = 30,
                    expiresAt = clock.now().plusSeconds(300),
                ),
        )

    companion object {
        private const val PACKAGE = "com.example.fixture"
    }
}

class NodeTokenRegistryTest {
    private val clock = SnapshotMutableClock(Instant.parse("2026-09-05T02:00:00Z"))
    private var seed = 0
    private val registry =
        NodeTokenRegistry(clock) {
            seed += 1
            ByteArray(16) { seed.toByte() }
        }
    private val binding = NodeTokenBinding("com.example.fixture", 12, 4, "fingerprint", listOf(0, 2))

    @Test
    fun independentlyRejectsEveryBindingMismatchAndExpiry() {
        val token = registry.issue(binding)

        assertEquals(NodeTokenResolutionStatus.PACKAGE_MISMATCH, resolve(token, packageName = "other").status)
        assertEquals(NodeTokenResolutionStatus.WINDOW_MISMATCH, resolve(token, windowId = 13).status)
        assertEquals(NodeTokenResolutionStatus.GENERATION_MISMATCH, resolve(token, generation = 5).status)
        assertEquals(NodeTokenResolutionStatus.FINGERPRINT_MISMATCH, resolve(token, fingerprint = "other").status)
        assertEquals(NodeTokenResolutionStatus.VALID, resolve(token).status)

        clock.instant = clock.now().plus(NodeTokenRegistry.TOKEN_TTL)
        assertEquals(NodeTokenResolutionStatus.EXPIRED, resolve(token).status)
        assertEquals(NodeTokenResolutionStatus.UNKNOWN, resolve(token).status)
    }

    @Test
    fun newSnapshotInvalidatesEveryPriorOpaqueToken() {
        val token = registry.issue(binding)
        registry.beginSnapshot()

        assertEquals(NodeTokenResolutionStatus.UNKNOWN, resolve(token).status)
    }

    private fun resolve(
        token: String,
        packageName: String = binding.packageName,
        windowId: Int = binding.windowId,
        generation: Long = binding.generation,
        fingerprint: String = binding.fingerprint,
    ) = registry.resolve(token, packageName, windowId, generation, fingerprint)
}

private class SnapshotMutableClock(
    var instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}

@Suppress("LongParameterList")
private class FakeSnapshotNode(
    override val packageName: String?,
    override val windowId: Int,
    override val className: String? = "android.view.View",
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val viewId: String? = null,
    override val bounds: AutomationNodeBounds = AutomationNodeBounds(0, 0, 100, 100),
    override val clickable: Boolean = false,
    override val longClickable: Boolean = false,
    override val editable: Boolean = false,
    override val scrollable: Boolean = false,
    override val enabled: Boolean = true,
    override val password: Boolean = false,
    override val accessibilityDataSensitive: Boolean = false,
    private val children: List<FakeSnapshotNode> = emptyList(),
    private val throwOnChildRead: Boolean = false,
) : SnapshotNode {
    var recycleCount = 0
        private set

    override val childCount: Int
        get() = children.size

    override fun childAt(index: Int): SnapshotNode? {
        if (throwOnChildRead) error("fixture child read failed")
        return children[index]
    }

    override fun performAction(
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean = false

    override fun recycle() {
        recycleCount += 1
    }
}
