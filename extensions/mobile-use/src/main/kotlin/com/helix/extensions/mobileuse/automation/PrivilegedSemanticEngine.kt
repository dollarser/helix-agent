package com.helix.extensions.mobileuse.automation

import android.view.accessibility.AccessibilityNodeInfo
import com.helix.core.policy.AutomationSessionScope
import java.time.Instant

/** Called only by the shell/root adapter. Reuses the ordinary semantic redaction/action contract. */
class PrivilegedSemanticEngine(
    packageName: String,
    wholePhone: Boolean,
    permissionControllerPackage: String? = null,
    installerPackage: String? = null,
) {
    private val permissionController = permissionControllerPackage?.takeIf { wholePhone && it == packageName }
    private val installer = installerPackage?.takeIf { wholePhone && it == packageName }
    private var sequence = 0
    private val registry = NodeTokenRegistry { ByteArray(16).also { it[0] = (++sequence).toByte() } }
    private val session =
        ActiveAutomationSession(
            "privileged-observation",
            Instant.now(),
            AutomationSessionScope(
                if (wholePhone) emptySet() else setOf(packageName),
                emptySet(),
                0,
                Instant.MAX,
                wholePhone,
            ),
        )

    fun capture(root: AccessibilityNodeInfo?): AutomationSnapshotResult {
        sequence = 0
        return AutomationSnapshotEngine(registry).capture(
            root?.let {
                AndroidSnapshotNode(it, permissionController, installer)
            },
            session,
            0,
        )
    }

    fun action(
        root: AccessibilityNodeInfo?,
        request: AutomationNodeActionRequest,
        allowed: () -> Boolean,
    ): AutomationActionResult =
        AutomationNodeActionExecutor(registry).execute(
            root?.let { GuardedSemanticNode(AndroidSnapshotNode(it, permissionController, installer), allowed) },
            session,
            0,
            request,
        )
}

private class GuardedSemanticNode(
    private val node: SnapshotNode,
    private val allowed: () -> Boolean,
) : SnapshotNode by node {
    override fun childAt(index: Int): SnapshotNode? = node.childAt(index)?.let { GuardedSemanticNode(it, allowed) }

    override fun performAction(
        action: Int,
        arguments: android.os.Bundle?,
    ): Boolean = allowed() && node.performAction(action, arguments)

    override fun setText(value: String): Boolean = allowed() && node.setText(value)

    override fun imeEnter(): Boolean = allowed() && node.imeEnter()

    override fun setProgress(value: Float): Boolean = allowed() && node.setProgress(value)
}
