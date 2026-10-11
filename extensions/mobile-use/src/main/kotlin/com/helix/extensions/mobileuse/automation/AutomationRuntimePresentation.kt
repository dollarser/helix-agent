package com.helix.extensions.mobileuse.automation

import com.helix.tools.framework.ExecutableToolCall

/** Optional plugin-owned native presentation. The automation executor owns no overlay UI. */
interface AutomationRuntimePresentation {
    fun bind(call: ExecutableToolCall): Boolean

    fun stopBoundTask(task: com.helix.extensions.plugin.PluginTaskIdentity)

    fun executionAllowed(): Boolean

    fun ownsWindow(windowId: Int): Boolean

    /** Off-main, bounded wait until overlays are absent from rendering/input; null fails closed. */
    fun hideForOperation(call: ExecutableToolCall): AutoCloseable?

    fun hide()

    fun refreshSurface() {}

    fun close()
}

object AutomationRuntimePresentationFactory {
    private val usage =
        ThreadLocal<
            com.helix.extensions.plugin.NativeResource.Lease<AutomationRuntimePresentation>?,
        >()
    private val current =
        java.util.concurrent.atomic.AtomicReference<
            com.helix.extensions.plugin.NativeResource<AutomationRuntimePresentation>?,
        >(null)

    fun presentation(): AutomationRuntimePresentation? =
        usage.get()?.value ?: current.get()?.let {
            if (it.state == com.helix.extensions.plugin.NativeResource.State.ACTIVE) it.value else null
        }

    fun borrow(): com.helix.extensions.plugin.NativeResource.Lease<AutomationRuntimePresentation>? =
        usage.get()?.retain() ?: current.get()?.borrow()

    internal fun <T> withUsage(
        reference: com.helix.extensions.plugin.NativeResource.Lease<AutomationRuntimePresentation>,
        block: () -> T,
    ): T {
        val previous = usage.get()
        usage.set(reference)
        return try {
            block()
        } finally {
            if (previous == null) usage.remove() else usage.set(previous)
        }
    }

    fun register(resource: com.helix.extensions.plugin.NativeResource<AutomationRuntimePresentation>): AutoCloseable {
        check(current.compareAndSet(null, resource)) { "MOBILE_USE_PRESENTATION_ALREADY_REGISTERED" }
        return AutoCloseable {
            current.compareAndSet(resource, null)
            try {
                resource.value.hide()
            } finally {
                resource.close()
            }
        }
    }
}
