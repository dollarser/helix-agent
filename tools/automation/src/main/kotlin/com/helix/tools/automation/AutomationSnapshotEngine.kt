package com.helix.tools.automation

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import java.util.concurrent.atomic.AtomicLong

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

    fun recycle()
}

internal class AndroidSnapshotNode(
    private val node: AccessibilityNodeInfo,
) : SnapshotNode {
    override val packageName: String?
        get() = node.packageName?.toString()
    override val windowId: Int
        get() = node.windowId
    override val className: String?
        get() = node.className?.toString()
    override val text: String?
        get() = node.text?.toString()
    override val contentDescription: String?
        get() = node.contentDescription?.toString()
    override val viewId: String?
        get() = node.viewIdResourceName
    override val bounds: AutomationNodeBounds
        get() {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return AutomationNodeBounds(rect.left, rect.top, rect.right, rect.bottom)
        }
    override val clickable: Boolean
        get() = node.isClickable
    override val longClickable: Boolean
        get() = node.isLongClickable
    override val editable: Boolean
        get() = node.isEditable
    override val scrollable: Boolean
        get() = node.isScrollable
    override val enabled: Boolean
        get() = node.isEnabled
    override val password: Boolean
        get() = node.isPassword
    override val accessibilityDataSensitive: Boolean
        get() = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive
    override val childCount: Int
        get() = node.childCount

    override fun childAt(index: Int): SnapshotNode? = node.getChild(index)?.let(::AndroidSnapshotNode)

    @Suppress("DEPRECATION")
    override fun recycle() {
        node.recycle()
    }
}

internal class AccessibilityGenerationTracker {
    private val generation = AtomicLong(1L)

    fun current(): Long = generation.get()

    fun contentChanged(): Long = generation.incrementAndGet()
}

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

            val nodePackage = node.packageName
            val nodeWindowId = node.windowId
            if ((nodePackage != null && nodePackage != state.packageName) || nodeWindowId != state.windowId) {
                state.abortStatus = AutomationSnapshotStatus.TARGET_CHANGED
                return
            }

            val className = state.bound(node.className)
            val text = state.bound(node.text)
            val description = state.bound(node.contentDescription)
            val viewId = state.bound(node.viewId)
            val bounds = node.bounds
            val clickable = node.clickable
            val longClickable = node.longClickable
            val editable = node.editable
            val scrollable = node.scrollable
            val enabled = node.enabled
            if (node.password || node.accessibilityDataSensitive) {
                state.abortStatus = AutomationSnapshotStatus.SENSITIVE_UI
                return
            }
            val fingerprint =
                nodeFingerprint(
                    listOf(
                        state.packageName,
                        state.windowId.toString(),
                        path.joinToString("."),
                        className,
                        text,
                        description,
                        viewId,
                        bounds.toString(),
                        clickable.toString(),
                        longClickable.toString(),
                        editable.toString(),
                        scrollable.toString(),
                        enabled.toString(),
                    ),
                )
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
                    bounds = bounds,
                    clickable = clickable,
                    longClickable = longClickable,
                    editable = editable,
                    scrollable = scrollable,
                    enabled = enabled,
                )
            state.hasUsefulSemantics =
                state.hasUsefulSemantics ||
                !text.isNullOrBlank() ||
                !description.isNullOrBlank() ||
                clickable ||
                longClickable ||
                editable ||
                scrollable

            val childCount = node.childCount
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

        override fun isDeniedPackage(packageName: String): Boolean = packageName in deniedPackages
    }
}
