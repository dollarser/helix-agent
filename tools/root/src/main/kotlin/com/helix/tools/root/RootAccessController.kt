package com.helix.tools.root

enum class RootGrantState {
    UNAVAILABLE,
    REQUESTING,
    DENIED,
    GRANTED,
    LOST,
}

enum class RootServiceState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class RootAccessStatus(
    val grant: RootGrantState,
    val service: RootServiceState,
)

enum class RootRequestStatus {
    STARTED,
    ALREADY_REQUESTING,
    ALREADY_GRANTED,
}

internal enum class RootRequestOutcome {
    UNAVAILABLE,
    DENIED,
    GRANTED,
}

internal interface RootAccessDriver {
    /** A read-only libsu observation. This must never construct a shell or show a grant prompt. */
    fun cachedGrant(): Boolean?

    fun requestRoot(onResult: (RootRequestOutcome) -> Unit)

    fun bindRootService(
        onConnected: (processId: Int) -> Unit,
        onLost: () -> Unit,
    )

    fun disconnect()

    fun execute(request: RootOperationRequest): RootOperationResult
}

/**
 * HXA-094 Root grant/RootService lifecycle spike. It deliberately exposes no command execution.
 * The only path that may construct a root shell is [requestRoot], which is called by an explicit
 * user action. Profile changes and status reads are observational and never request capability.
 */
class RootAccessController internal constructor(
    private val driver: RootAccessDriver,
) {
    private var grant = RootGrantState.UNAVAILABLE
    private var service = RootServiceState.DISCONNECTED
    private var serviceProcessId: Int? = null

    @Synchronized
    fun status(): RootAccessStatus {
        if (grant == RootGrantState.GRANTED && driver.cachedGrant() == false) {
            markLost()
        }
        return RootAccessStatus(grant, service)
    }

    @Synchronized
    fun execute(request: RootOperationRequest): RootOperationResult {
        status()
        if (grant != RootGrantState.GRANTED || service != RootServiceState.CONNECTED) {
            return RootOperationResult.Failed("ROOT_NOT_CONNECTED")
        }
        return driver.execute(request)
    }

    /** Runtime profile changes are not authorization and must not call requestRoot. */
    @Suppress("UNUSED_PARAMETER")
    fun onProfileChanged(isAdvanced: Boolean): RootAccessStatus = status()

    @Synchronized
    fun requestRoot(): RootRequestStatus =
        when (grant) {
            RootGrantState.REQUESTING -> {
                RootRequestStatus.ALREADY_REQUESTING
            }

            RootGrantState.GRANTED -> {
                RootRequestStatus.ALREADY_GRANTED
            }

            RootGrantState.UNAVAILABLE,
            RootGrantState.DENIED,
            RootGrantState.LOST,
            -> {
                grant = RootGrantState.REQUESTING
                service = RootServiceState.DISCONNECTED
                serviceProcessId = null
                driver.requestRoot(::rootRequestCompleted)
                RootRequestStatus.STARTED
            }
        }

    @Synchronized
    fun disconnect() {
        driver.disconnect()
        grant = RootGrantState.UNAVAILABLE
        service = RootServiceState.DISCONNECTED
        serviceProcessId = null
    }

    @Synchronized
    internal fun rootServiceProcessIdForTest(): Int? = serviceProcessId

    @Synchronized
    private fun rootRequestCompleted(outcome: RootRequestOutcome) {
        if (grant != RootGrantState.REQUESTING) return
        when (outcome) {
            RootRequestOutcome.UNAVAILABLE -> {
                grant = RootGrantState.UNAVAILABLE
            }

            RootRequestOutcome.DENIED -> {
                grant = RootGrantState.DENIED
            }

            RootRequestOutcome.GRANTED -> {
                grant = RootGrantState.GRANTED
                service = RootServiceState.CONNECTING
                driver.bindRootService(::rootServiceConnected, ::markLost)
            }
        }
    }

    @Synchronized
    private fun rootServiceConnected(processId: Int) {
        if (grant != RootGrantState.GRANTED || service != RootServiceState.CONNECTING) return
        if (processId <= 0) {
            markLost()
            return
        }
        serviceProcessId = processId
        service = RootServiceState.CONNECTED
    }

    @Synchronized
    private fun markLost() {
        if (grant != RootGrantState.GRANTED) return
        driver.disconnect()
        grant = RootGrantState.LOST
        service = RootServiceState.DISCONNECTED
        serviceProcessId = null
    }
}
