package com.helix.tools.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock

internal interface SnapshotNode {
    val packageName: String?
    val windowId: Int
    val className: String?
    val text: String?
    val contentDescription: String?
    val viewId: String?
    val bounds: AutomationNodeBounds
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val enabled: Boolean
    val password: Boolean
    val accessibilityDataSensitive: Boolean
    val childCount: Int

    fun childAt(index: Int): SnapshotNode?

    fun performAction(
        action: Int,
        arguments: Bundle? = null,
    ): Boolean

    fun recycle()
}

internal data class ObservedSnapshotNode(
    val packageName: String?,
    val windowId: Int,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: AutomationNodeBounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val accessibilityDataSensitive: Boolean,
    val childCount: Int,
)

internal fun SnapshotNode.observe(): ObservedSnapshotNode =
    ObservedSnapshotNode(
        packageName = packageName,
        windowId = windowId,
        className = className?.take(AutomationSnapshotEngine.MAX_FIELD_CHARACTERS),
        text = text?.take(AutomationSnapshotEngine.MAX_FIELD_CHARACTERS),
        contentDescription =
            contentDescription?.take(AutomationSnapshotEngine.MAX_FIELD_CHARACTERS),
        viewId = viewId?.take(AutomationSnapshotEngine.MAX_FIELD_CHARACTERS),
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        editable = editable,
        scrollable = scrollable,
        enabled = enabled,
        password = password,
        accessibilityDataSensitive = accessibilityDataSensitive,
        childCount = childCount,
    )

internal fun ObservedSnapshotNode.fingerprint(
    expectedPackage: String,
    expectedWindowId: Int,
    path: List<Int>,
): String =
    nodeFingerprint(
        listOf(
            expectedPackage,
            expectedWindowId.toString(),
            path.joinToString("."),
            className,
            text,
            contentDescription,
            viewId,
            bounds.toString(),
            clickable.toString(),
            longClickable.toString(),
            editable.toString(),
            scrollable.toString(),
            enabled.toString(),
        ),
    )

internal class AutomationSnapshotEngine(
    private val tokenRegistry: NodeTokenRegistry,
    private val clock: Clock = SystemClock(),
    private val sensitiveTargetPolicy: SensitiveAutomationTargetPolicy = SensitiveAutomationTargetPolicy,
) {
    @Suppress("ReturnCount")
    fun capture(
        root: SnapshotNode?,
        session: ActiveAutomationSession,
        generation: Long,
    ): AutomationSnapshotResult {
        // Public Accessibility APIs do not distinguish an ordinary transient null root from a
        // FLAG_SECURE/custom surface that withholds its tree. Collapse both to the required
        // fail-closed UNSUPPORTED_UI result rather than guessing why the platform hid it.
        if (root == null) return result(AutomationSnapshotStatus.UNSUPPORTED_UI)

        val packageName: String
        val windowId: Int
        try {
            packageName = root.packageName.orEmpty()
            windowId = root.windowId
        } catch (_: RuntimeException) {
            root.recycleSafely()
            tokenRegistry.invalidate()
            return result(AutomationSnapshotStatus.UNSUPPORTED_UI)
        }
        if (packageName.isBlank() || windowId < 0) {
            root.recycleSafely()
            tokenRegistry.invalidate()
            return result(AutomationSnapshotStatus.UNSUPPORTED_UI)
        }
        if (packageName !in session.scope.allowedPackages) {
            root.recycleSafely()
            tokenRegistry.invalidate()
            return result(AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED)
        }
        if (sensitiveTargetPolicy.isDeniedPackage(packageName)) {
            root.recycleSafely()
            tokenRegistry.invalidate()
            return result(AutomationSnapshotStatus.SENSITIVE_UI)
        }

        tokenRegistry.beginSnapshot()
        val state = TraversalState(packageName, windowId, generation)
        visitOwned(root, parentToken = null, depth = 0, path = emptyList(), state = state)
        state.abortStatus?.let { status ->
            tokenRegistry.invalidate()
            return result(status)
        }
        if (!state.hasUsefulSemantics || state.nodes.isEmpty()) {
            tokenRegistry.invalidate()
            return result(AutomationSnapshotStatus.UNSUPPORTED_UI)
        }
        return AutomationSnapshotResult(
            status = AutomationSnapshotStatus.SUCCESS,
            snapshot =
                AutomationSnapshot(
                    packageName = packageName,
                    windowId = windowId,
                    generation = generation,
                    createdAt = clock.now(),
                    nodes = state.nodes.toList(),
                    truncated = state.truncated,
                ),
        )
    }

    // One ownership scope deliberately spans read, policy, token issue, child walk and recycle;
    // splitting those stages across owners would make an AccessibilityNodeInfo leak easier.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun visitOwned(
        node: SnapshotNode,
        parentToken: String?,
        depth: Int,
        path: List<Int>,
        state: TraversalState,
    ) {
        try {
            if (state.abortStatus != null) return
            if (depth > MAX_DEPTH || state.nodes.size >= MAX_NODES) {
                state.truncated = true
                return
            }

            val observed = node.observe()
            if (
                (observed.packageName != null && observed.packageName != state.packageName) ||
                observed.windowId != state.windowId
            ) {
                state.abortStatus = AutomationSnapshotStatus.TARGET_CHANGED
                return
            }

            val className = state.bound(observed.className)
            val text = state.bound(observed.text)
            val description = state.bound(observed.contentDescription)
            val viewId = state.bound(observed.viewId)
            if (observed.password || observed.accessibilityDataSensitive) {
                state.abortStatus = AutomationSnapshotStatus.SENSITIVE_UI
                return
            }
            val fingerprint = observed.fingerprint(state.packageName, state.windowId, path)
            val token =
                tokenRegistry.issue(
                    NodeTokenBinding(
                        packageName = state.packageName,
                        windowId = state.windowId,
                        generation = state.generation,
                        fingerprint = fingerprint,
                        path = path,
                    ),
                )
            state.nodes +=
                AutomationSnapshotNode(
                    token = token,
                    parentToken = parentToken,
                    depth = depth,
                    className = className,
                    text = text,
                    contentDescription = description,
                    viewId = viewId,
                    bounds = observed.bounds,
                    clickable = observed.clickable,
                    longClickable = observed.longClickable,
                    editable = observed.editable,
                    scrollable = observed.scrollable,
                    enabled = observed.enabled,
                )
            state.hasUsefulSemantics =
                state.hasUsefulSemantics ||
                !text.isNullOrBlank() ||
                !description.isNullOrBlank() ||
                observed.clickable ||
                observed.longClickable ||
                observed.editable ||
                observed.scrollable

            val childCount = observed.childCount
            if (depth == MAX_DEPTH && childCount > 0) {
                state.truncated = true
                return
            }
            for (index in 0 until childCount) {
                if (state.nodes.size >= MAX_NODES) {
                    state.truncated = true
                    return
                }
                val child = node.childAt(index) ?: continue
                visitOwned(child, token, depth + 1, path + index, state)
                if (state.abortStatus != null) return
            }
        } catch (_: RuntimeException) {
            state.abortStatus = AutomationSnapshotStatus.UNSUPPORTED_UI
        } finally {
            node.recycleSafely()
        }
    }

    private class TraversalState(
        val packageName: String,
        val windowId: Int,
        val generation: Long,
    ) {
        val nodes = mutableListOf<AutomationSnapshotNode>()
        var remainingCharacters = MAX_TOTAL_CHARACTERS
        var hasUsefulSemantics = false
        var truncated = false
        var abortStatus: AutomationSnapshotStatus? = null

        @Suppress("ReturnCount")
        fun bound(value: String?): String? {
            if (value.isNullOrEmpty()) return null
            if (remainingCharacters == 0) {
                truncated = true
                return null
            }
            val allowed = minOf(value.length, MAX_FIELD_CHARACTERS, remainingCharacters)
            if (allowed < value.length) truncated = true
            remainingCharacters -= allowed
            return value.take(allowed)
        }
    }

    private fun SnapshotNode.recycleSafely() {
        try {
            recycle()
        } catch (_: RuntimeException) {
            // Recycling is best-effort on API 33+, but a platform failure must never turn a
            // refused/unsupported snapshot into success or retain another live node reference.
        }
    }

    private fun result(status: AutomationSnapshotStatus) = AutomationSnapshotResult(status)

    companion object {
        const val MAX_NODES = NodeTokenRegistry.MAX_TOKENS
        const val MAX_DEPTH = 16
        const val MAX_FIELD_CHARACTERS = 256
        const val MAX_TOTAL_CHARACTERS = 16_384
    }
}

internal fun interface SensitiveAutomationTargetPolicy {
    fun isDeniedPackage(packageName: String): Boolean

    companion object : SensitiveAutomationTargetPolicy {
        private val deniedPackages =
            setOf(
                "com.android.settings",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.android.systemui",
                "com.topjohnwu.magisk",
                "me.bmax.apatch",
                "me.weishu.kernelsu",
                "com.kernelsu",
            )

        private val deniedPackageMarkers =
            setOf(
                "bank",
                "wallet",
                "payment",
                "password",
                "authenticator",
                "biometric",
            )

        override fun isDeniedPackage(packageName: String): Boolean =
            packageName in deniedPackages ||
                packageName
                    .lowercase()
                    .split('.', '_', '-')
                    .any { segment -> deniedPackageMarkers.any(segment::contains) }
    }
}
