package com.helix.app

interface ShellRepository {
    val destinations: List<ShellDestination>
    val initialDestination: ShellDestination
}

internal class FakeShellRepository : ShellRepository {
    override val destinations: List<ShellDestination> = ShellDestination.entries
    override val initialDestination: ShellDestination = ShellDestination.Sessions
}
