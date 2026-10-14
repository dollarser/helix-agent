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

    @Test fun retiredCallbackBorrowsItsOriginalInstanceAfterReplacementPublication() {
        val old = Surface()
        val resource = NativeResource<AutomationRuntimePresentation>(old) { it.close() }
        val registration = AutomationRuntimePresentationFactory.register(resource)
        val callback = requireNotNull(AutomationRuntimePresentationFactory.borrow())
        registration.close()
        val next = Surface()
        val replacement = NativeResource<AutomationRuntimePresentation>(next) { it.close() }
        try {
            AutomationRuntimePresentationFactory.register(replacement).use {
                AutomationRuntimePresentationFactory.withUsage(callback) {
                    requireNotNull(AutomationRuntimePresentationFactory.borrow()).use { retained ->
                        assertSame(old, retained.value)
                        assertSame(old, AutomationRuntimePresentationFactory.presentation())
                        assertEquals(false, retained.accepting)
                        assertEquals(0, old.closed)
                        assertEquals(0, next.closed)
                    }
                }
                assertSame(next, AutomationRuntimePresentationFactory.presentation())
                callback.close()
                assertEquals(1, old.closed)
                assertEquals(0, next.closed)
            }
        } finally {
            callback.close()
            registration.close()
        }
        assertEquals(1, next.closed)
    }

    @Test fun hideFailureRetainsCleanupFailureAndDoesNotBlockAReplacement() {
        val hide = IllegalStateException("hide failed")
        val cleanup = IllegalArgumentException("cleanup failed")
        val old = Surface(hideFailure = hide, closeFailure = cleanup)
        val resource = NativeResource<AutomationRuntimePresentation>(old) { it.close() }
        val registration = AutomationRuntimePresentationFactory.register(resource)
        val reported = assertThrows(IllegalStateException::class.java) { registration.close() }
        assertSame(hide, reported)
        assertSame(cleanup, reported.suppressed.single())
        assertSame(cleanup, resource.failure)
        assertEquals(NativeResource.State.CLEANUP_FAILED, resource.state)
        assertNull(AutomationRuntimePresentationFactory.presentation())
        val next = Surface()
        AutomationRuntimePresentationFactory
            .register(
                NativeResource<AutomationRuntimePresentation>(next) { it.close() },
            ).use {
                assertSame(next, AutomationRuntimePresentationFactory.presentation())
                assertEquals(0, next.closed)
            }
        assertEquals(1, old.closed)
        assertEquals(1, next.closed)
    }

    @Test fun hideFailureStillCompletesSuccessfulCleanupAndPropagatesTheOriginalFailure() {
        val failure = IllegalStateException("hide failed")
        val old = Surface(hideFailure = failure)
        val resource = NativeResource<AutomationRuntimePresentation>(old) { it.close() }
        val registration = AutomationRuntimePresentationFactory.register(resource)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { registration.close() })
        assertEquals(0, failure.suppressed.size)
        assertEquals(1, old.closed)
        assertEquals(NativeResource.State.CLOSED, resource.state)
        assertNull(resource.failure)
        assertNull(AutomationRuntimePresentationFactory.presentation())
    }

    @Test fun identicalHideAndCleanupFailureDoesNotAttemptSelfSuppression() {
        val failure = AssertionError("same failure")
        val old = Surface(hideFailure = failure, closeFailure = failure)
        val resource = NativeResource<AutomationRuntimePresentation>(old) { it.close() }
        val registration = AutomationRuntimePresentationFactory.register(resource)
        assertSame(failure, assertThrows(AssertionError::class.java) { registration.close() })
        assertEquals(0, failure.suppressed.size)
        assertSame(failure, resource.failure)
        assertEquals(NativeResource.State.CLEANUP_FAILED, resource.state)
        assertNull(AutomationRuntimePresentationFactory.presentation())
    }

    private class Surface(
        private val hideFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
    ) : AutomationRuntimePresentation {
        var closed = 0

        override fun bind(call: ExecutableToolCall) = true

        override fun stopBoundTask(task: com.helix.extensions.plugin.PluginTaskIdentity) = Unit

        override fun executionAllowed() = true

        override fun ownsWindow(windowId: Int) = false

        override fun hideForOperation(call: ExecutableToolCall) = AutoCloseable { }

        override fun hide() {
            hideFailure?.let { throw it }
        }

        override fun close() {
            closed++
            closeFailure?.let { throw it }
        }
    }
}
