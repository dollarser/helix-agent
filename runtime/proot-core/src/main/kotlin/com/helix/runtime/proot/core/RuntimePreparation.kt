package com.helix.runtime.proot.core

class RuntimePreparationRequired(
    val state: RuntimeInstallationState,
) : IllegalStateException("Environment requires maintenance: $state")

/** One bundled environment has one non-reentrant installer. Prepared executions do not take this lock. */
class RuntimePreparation {
    fun ensurePrepared(
        inspect: () -> RuntimeInstallation,
        install: () -> InstallOutcome,
    ): RuntimeInstallation {
        val current = inspect()
        if (current.state == RuntimeInstallationState.PREPARED) return current
        return synchronized(this) {
            val latest = inspect()
            when (latest.state) {
                RuntimeInstallationState.PREPARED -> {
                    latest
                }

                RuntimeInstallationState.NOT_PREPARED -> {
                    val result = install()
                    check(result is InstallOutcome.Success) { "Environment preparation failed: $result" }
                    inspect().also {
                        check(
                            it.state == RuntimeInstallationState.PREPARED,
                        ) { "Installation is not ready" }
                    }
                }

                else -> {
                    throw RuntimePreparationRequired(latest.state)
                }
            }
        }
    }
}
