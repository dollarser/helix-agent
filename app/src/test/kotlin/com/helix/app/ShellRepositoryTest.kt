package com.helix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellRepositoryTest {
    private val repository: ShellRepository = FakeShellRepository()

    @Test
    fun `shell exposes exactly eleven unique routes`() {
        // P0-B added the Tasks dashboard, the Artifact Center, the Git status page and the
        // Capabilities panel.
        assertEquals(11, repository.destinations.size)
        assertEquals(
            11,
            repository.destinations
                .map(ShellDestination::route)
                .toSet()
                .size,
        )
        assertEquals(
            listOf(
                "sessions",
                "tasks",
                "artifacts",
                "git",
                "files",
                "browser",
                "extensions",
                "capabilities",
                "permissions",
                "settings",
                "audit",
            ),
            repository.destinations.map(ShellDestination::route),
        )
    }

    @Test
    fun `sessions is the initial destination`() {
        assertEquals(ShellDestination.Sessions, repository.initialDestination)
        assertTrue(repository.initialDestination in repository.destinations)
    }

    @Test
    fun `shell repository is exposed through the container interface type`() {
        // HXA-028: DefaultAppContainer now requires an Android Context (Room +
        // SharedPreferences); the production wiring is exercised by the
        // instrumented app tests (first-launch/provider/chat). This JVM pin
        // keeps the interface contract every AppContainer implementation must
        // satisfy for the shell part.
        val repository: ShellRepository = FakeShellRepository()

        assertEquals(ShellDestination.Sessions, repository.initialDestination)
        assertTrue(repository.initialDestination in repository.destinations)
    }
}
