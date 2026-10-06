package com.helix.extensions.mobileuse.automation

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
    val range: AutomationNodeRange? get() = null
    val canSetProgress: Boolean get() = false
    val canImeEnter: Boolean get() = false
    val checkable: Boolean get() = false
    val checked: Boolean get() = false

    fun childAt(index: Int): SnapshotNode?

    fun performAction(
        action: Int,
        arguments: Bundle? = null,
    ): Boolean

    fun setText(value: String): Boolean = false

    fun imeEnter(): Boolean = false

    fun setProgress(value: Float): Boolean = false

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
    val range: AutomationNodeRange? = null,
    val canSetProgress: Boolean = false,
    val canImeEnter: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val fieldTruncated: Boolean = false,
)

internal fun SnapshotNode.observe(): ObservedSnapshotNode {
    var fieldTruncated = false

    fun bounded(value: String?): String? {
        if (value != null && value.length > AutomationSnapshotEngine.MAX_FIELD_CHARACTERS) fieldTruncated = true
        return value?.take(AutomationSnapshotEngine.MAX_FIELD_CHARACTERS)
    }
    return ObservedSnapshotNode(
        packageName = packageName,
        windowId = windowId,
        className = bounded(className),
        text = bounded(text),
        contentDescription =
            bounded(contentDescription),
        viewId = bounded(viewId),
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        editable = editable,
        scrollable = scrollable,
        enabled = enabled,
        password = password,
        accessibilityDataSensitive = accessibilityDataSensitive,
        childCount = childCount,
        range = range?.takeIf { it.accepts(it.current.toDouble()) },
        canSetProgress = canSetProgress,
        canImeEnter = canImeEnter,
        checkable = checkable,
        checked = checked,
        fieldTruncated = fieldTruncated,
    )
}

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
            checkable.toString(),
            checked.toString(),
        ) + if (range != null || canSetProgress) listOf(range.toString(), canSetProgress.toString()) else emptyList(),
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
        if (!session.scope.permitsPackage(packageName)) {
            root.recycleSafely()
            tokenRegistry.invalidate()
            return AutomationSnapshotResult(
                AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED,
                targetPackage = packageName,
            )
        }
        if (!session.scope.allApplications &&
            sensitiveTargetPolicy.isDeniedPackage(packageName, session.allowSystemSettings)
        ) {
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
                    truncationReasons = state.truncationReasons.toSet(),
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
                state.truncate(if (depth > MAX_DEPTH) "DEPTH_LIMIT" else "NODE_LIMIT")
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

            if (observed.password || observed.accessibilityDataSensitive) {
                // A protected field must not hide unrelated controls on an otherwise authorized page.
                state.nodes +=
                    AutomationSnapshotNode(
                        "",
                        parentToken,
                        depth,
                        observed.className,
                        null,
                        "Protected field",
                        null,
                        observed.bounds,
                        false,
                        false,
                        false,
                        false,
                        observed.enabled,
                        redacted = true,
                    )
                state.hasUsefulSemantics = true
                if (!observed.password) visitChildren(node, observed.childCount, null, depth, path, state)
                return
            }
            if (observed.fieldTruncated) state.truncate("FIELD_LIMIT")
            val text = state.bound(observed.text)
            val description = state.bound(observed.contentDescription)
            val className = state.bound(observed.className)
            val viewId = state.bound(observed.viewId)
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
                    range = observed.range,
                    canSetProgress = observed.canSetProgress && observed.range != null,
                    canImeEnter = observed.canImeEnter,
                    checkable = observed.checkable,
                    checked = observed.checked,
                )
            state.hasUsefulSemantics =
                state.hasUsefulSemantics ||
                !text.isNullOrBlank() ||
                !description.isNullOrBlank() ||
                observed.clickable ||
                observed.longClickable ||
                observed.editable ||
                observed.scrollable ||
                observed.checkable ||
                observed.canSetProgress

            visitChildren(node, observed.childCount, token, depth, path, state)
        } catch (_: RuntimeException) {
            state.abortStatus = AutomationSnapshotStatus.UNSUPPORTED_UI
        } finally {
            node.recycleSafely()
        }
    }

    @Suppress("ReturnCount") // Each traversal bound exits immediately while visitOwned retains node ownership.
    private fun visitChildren(
        node: SnapshotNode,
        childCount: Int,
        parentToken: String?,
        depth: Int,
        path: List<Int>,
        state: TraversalState,
    ) {
        if (depth == MAX_DEPTH && childCount > 0) {
            state.truncate("DEPTH_LIMIT")
            return
        }
        for (index in 0 until childCount) {
            if (state.nodes.size >= MAX_NODES) {
                state.truncate("NODE_LIMIT")
                return
            }
            val child = node.childAt(index) ?: continue
            visitOwned(child, parentToken, depth + 1, path + index, state)
            if (state.abortStatus != null) return
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
        val truncationReasons = linkedSetOf<String>()
        val truncated: Boolean get() = truncationReasons.isNotEmpty()

        fun truncate(reason: String) {
            truncationReasons += reason
        }

        var abortStatus: AutomationSnapshotStatus? = null

        @Suppress("ReturnCount")
        fun bound(value: String?): String? {
            if (value.isNullOrEmpty()) return null
            if (remainingCharacters == 0) {
                truncate("TEXT_LIMIT")
                return null
            }
            val allowed = minOf(value.length, MAX_FIELD_CHARACTERS, remainingCharacters)
            if (value.length > MAX_FIELD_CHARACTERS) truncate("FIELD_LIMIT")
            if (value.length > remainingCharacters) truncate("TEXT_LIMIT")
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
        const val MAX_DEPTH = 64
        const val MAX_FIELD_CHARACTERS = 256
        const val MAX_TOTAL_CHARACTERS = 16_384
    }
}

internal fun interface SensitiveAutomationTargetPolicy {
    fun isDeniedPackage(packageName: String): Boolean

    fun isDeniedPackage(
        packageName: String,
        allowSystemSettings: Boolean,
    ): Boolean = isDeniedPackage(packageName)

    companion object : SensitiveAutomationTargetPolicy {
        override fun isDeniedPackage(
            packageName: String,
            allowSystemSettings: Boolean,
        ): Boolean =
            !(allowSystemSettings && packageName in SystemSettingsTargets.packages) &&
                isDeniedPackage(packageName)

        // Only explicitly selected system UI needs the matching user scope. App names and
        // categories are not evidence that a user-authorized operation must be rejected.
        override fun isDeniedPackage(packageName: String): Boolean = packageName in SystemSettingsTargets.packages
    }
}
