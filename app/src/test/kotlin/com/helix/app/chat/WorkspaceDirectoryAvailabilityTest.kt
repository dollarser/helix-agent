package com.helix.app.chat

import com.helix.core.workspace.ScopeNotAvailable
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WorkspaceDirectoryAvailabilityTest {
    @Test fun availableDirectoryKeepsItsExactIdentity() {
        assertEquals("scope:ws-a:sub", availableWorkspaceDirectory("scope:ws-a:sub") { it })
        assertNull(availableWorkspaceDirectory("scope:ws-a:sub") { "scope:ws-b:" })
    }

    @Test fun resourceFailuresAllowAnEmptyWorkspaceFallback() {
        listOf(
            IOException(),
            ScopeNotAvailable("missing"),
            IllegalArgumentException(),
            IllegalStateException(),
            SecurityException(),
        ).forEach { failure -> assertNull(availableWorkspaceDirectory("scope:ws-a:") { throw failure }) }
    }

    @Test fun cancellationAndUnexpectedFailuresCannotBecomeSuccessfulFallbacks() {
        assertThrows(CancellationException::class.java) {
            availableWorkspaceDirectory("scope:ws-a:") { throw CancellationException() }
        }
        assertThrows(UnsupportedOperationException::class.java) {
            availableWorkspaceDirectory("scope:ws-a:") { throw UnsupportedOperationException() }
        }
    }
}
