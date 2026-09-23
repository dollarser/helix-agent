package com.helix.app

interface ShellRepository {
    val destinations: List<ShellDestination>
    val initialDestination: ShellDestination
}

internal class FakeShellRepository(
    terminalAvailable: Boolean = false,
) : ShellRepository {
    override val destinations: List<ShellDestination> =
        if (terminalAvailable) {
            ShellDestination.entries
        } else {
            ShellDestination.entries.filter { it != ShellDestination.Terminal }
        }
    override val initialDestination: ShellDestination = ShellDestination.Sessions
}
