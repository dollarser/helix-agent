package com.helix.app

interface AppContainer {
    val shellRepository: ShellRepository
}

internal class DefaultAppContainer : AppContainer {
    override val shellRepository: ShellRepository = FakeShellRepository()
}
