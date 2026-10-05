package com.helix.extensions.mobileuse.automation

import com.helix.tools.framework.ExecutableToolCall

interface AutomationToolPort {
    fun accessibilityOnly(): AutomationToolPort = this

    fun preferredClickBackend(): AutomationClickBackend = AutomationClickBackend.ACCESSIBILITY

    fun rootClick(selector: AutomationPrivilegedSelector): AutomationActionResult =
        AutomationActionResult(AutomationActionStatus.ROOT_UNAVAILABLE)

    fun shizukuClick(selector: AutomationPrivilegedSelector): AutomationActionResult =
        AutomationActionResult(AutomationActionStatus.SHIZUKU_UNAVAILABLE)

    fun forCall(call: ExecutableToolCall): AutomationToolPort = this

    fun snapshot(): AutomationSnapshotResult

    fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult

    fun globalAction(action: AutomationGlobalAction): AutomationActionResult
}

class PermissionCenterAutomationToolPort(
    private val center: AutomationPermissionCenter,
    private val originalCall: ExecutableToolCall? = null,
    private val forceAccessibility: Boolean = false,
) : AutomationToolPort {
    override fun accessibilityOnly(): AutomationToolPort =
        PermissionCenterAutomationToolPort(center, originalCall, true)

    override fun preferredClickBackend(): AutomationClickBackend = center.preferredClickBackend()

    override fun rootClick(selector: AutomationPrivilegedSelector): AutomationActionResult =
        originalCall?.let { center.rootClick(it, selector) }
            ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)

    override fun shizukuClick(selector: AutomationPrivilegedSelector): AutomationActionResult =
        originalCall?.let { center.shizukuClick(it, selector) }
            ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)

    override fun forCall(call: ExecutableToolCall): AutomationToolPort =
        PermissionCenterAutomationToolPort(center, call, forceAccessibility)

    private fun <T> bound(block: () -> T): T? = originalCall?.let { center.withConversation(it, block) }

    override fun snapshot() =
        (if (forceAccessibility) bound { center.snapshot() } else originalCall?.let(center::semanticSnapshot))
            ?: AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)

    override fun nodeAction(request: AutomationNodeActionRequest) =
        originalCall?.let { center.semanticAction(it, request) }
            ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)

    override fun globalAction(action: AutomationGlobalAction) =
        bound { center.performGlobalAction(action) }
            ?: AutomationActionResult(AutomationActionStatus.NO_ACTIVE_SESSION)
}
