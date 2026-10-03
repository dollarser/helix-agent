package com.helix.tools.automation

import com.helix.tools.framework.ExecutableToolCall

/** Optional plugin-owned native presentation. The automation executor owns no overlay UI. */
interface AutomationRuntimePresentation {
    fun bind(call: ExecutableToolCall): Boolean

    fun executionAllowed(): Boolean

    fun ownsWindow(windowId: Int): Boolean

    /** Off-main, bounded wait until overlays are absent from rendering/input; null fails closed. */
    fun hideForOperation(call: ExecutableToolCall): AutoCloseable?

    fun hide()

    fun close()
}

object AutomationRuntimePresentationFactory {
    @Volatile
    var create: ((HelixAccessibilityService) -> AutomationRuntimePresentation)? = null
}
