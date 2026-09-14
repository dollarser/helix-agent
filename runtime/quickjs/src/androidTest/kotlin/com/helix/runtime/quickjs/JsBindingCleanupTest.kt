package com.helix.runtime.quickjs

import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class JsBindingCleanupTest : QuickJsDeviceTestHost() {
    @Test
    fun cancellationAfterAcceptedBindReleasesConnection() {
        checkCleanup(cancelAfterBinding = true, expected = JsExecutionStatus.CANCELLED)
    }

    @Test
    fun missingConnectionCallbackReleasesAcceptedBinding() {
        checkCleanup(cancelAfterBinding = false, expected = JsExecutionStatus.BIND_FAILED)
    }

    private fun checkCleanup(
        cancelAfterBinding: Boolean,
        expected: JsExecutionStatus,
    ) {
        val context = DelayedConnectionContext()
        try {
            val result =
                JsExecutionClient(context).execute(
                    JsExecutionTestSupport.params(JsExecutionTestSupport.newExecutionId("bind-cleanup"), "return 42"),
                    JsCancellation { cancelAfterBinding && context.accepted },
                )
            assertTrue("real isolated binding must be accepted", context.accepted)
            assertEquals(expected, result.status)
            assertEquals("accepted binding must be released on every early exit", 1, context.releases)
        } finally {
            // Keep the failing pre-fix test from leaking its real service connection.
            context.cleanup()
        }
    }

    private class DelayedConnectionContext : ContextWrapper(JsExecutionTestSupport.context) {
        var accepted = false
        var releases = 0
        private var connection: ServiceConnection? = null

        override fun bindIsolatedService(
            service: Intent,
            flags: Int,
            instanceName: String,
            executor: Executor,
            connection: ServiceConnection,
        ): Boolean {
            this.connection = connection
            // Bind the real service but withhold callback dispatch deterministically.
            accepted = super.bindIsolatedService(service, flags, instanceName, Executor { }, connection)
            return accepted
        }

        override fun unbindService(connection: ServiceConnection) {
            super.unbindService(connection)
            releases++
        }

        fun cleanup() {
            if (accepted && releases == 0) super.unbindService(requireNotNull(connection))
        }
    }
}
