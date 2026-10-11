package com.helix.extensions.mobileuse.automation

import com.helix.extensions.plugin.NativeResource
import com.helix.tools.framework.ExecutableToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationPresentationRegistrationTest {
    @Test fun oldCallbackAndRepeatedRetirementCannotReleaseTheReplacementPresentation() {
        val old = Surface()
        val resource = NativeResource<AutomationRuntimePresentation>(old) { it.close() }
        val registration = AutomationRuntimePresentationFactory.register(resource)
        val call = requireNotNull(AutomationRuntimePresentationFactory.borrow())
        try {
            val callback =
                AutomationRuntimePresentationFactory.withUsage(call) {
                    requireNotNull(AutomationRuntimePresentationFactory.borrow())
                }
            registration.close()
            call.close()
            assertNull(AutomationRuntimePresentationFactory.presentation())
            assertEquals(NativeResource.State.DRAINING, resource.state)
            val next = Surface()
            val nextResource = NativeResource<AutomationRuntimePresentation>(next) { it.close() }
            AutomationRuntimePresentationFactory.register(nextResource).use {
                registration.close()
                callback.close()
                assertEquals(1, old.closed)
                assertEquals(0, next.closed)
                assertSame(next, AutomationRuntimePresentationFactory.presentation())
                assertEquals(NativeResource.State.ACTIVE, nextResource.state)
            }
            assertEquals(1, next.closed)
        } finally {
            call.close()
            registration.close()
        }
    }

    @Test fun duplicateInitializationDoesNotReplaceOrCloseTheExistingPresentation() {
        val active = Surface()
        val resource = NativeResource<AutomationRuntimePresentation>(active) { it.close() }
        AutomationRuntimePresentationFactory.register(resource).use {
            val own = Surface()
            val ownResource = NativeResource<AutomationRuntimePresentation>(own) { it.close() }
            try {
                assertThrows(IllegalStateException::class.java) {
                    AutomationRuntimePresentationFactory.register(ownResource)
                }
            } finally {
                ownResource.close()
            }
            assertEquals(1, own.closed)
            assertEquals(0, active.closed)
            assertSame(active, AutomationRuntimePresentationFactory.presentation())
        }
    }

    private class Surface : AutomationRuntimePresentation {
        var closed = 0

        override fun bind(call: ExecutableToolCall) = true

        override fun stopBoundTask(task: com.helix.extensions.plugin.PluginTaskIdentity) = Unit

        override fun executionAllowed() = true

        override fun ownsWindow(windowId: Int) = false

        override fun hideForOperation(call: ExecutableToolCall) = AutoCloseable { }

        override fun hide() = Unit

        override fun close() {
            closed++
        }
    }
}
