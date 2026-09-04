package com.helix.tools.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootAccessControllerTest {
    @Test
    fun profileChangesAndStatusReadsNeverRequestRoot() {
        val driver = FakeRootDriver()
        val controller = RootAccessController(driver)

        assertEquals(unavailable(), controller.onProfileChanged(isAdvanced = true))
        assertEquals(unavailable(), controller.onProfileChanged(isAdvanced = false))
        assertEquals(unavailable(), controller.status())
        assertEquals(0, driver.requestCount)
        assertEquals(0, driver.bindCount)
        assertNull(driver.requestCallback)
    }

    @Test
    fun explicitRequestMapsUnavailableAndDenialWithoutBinding() {
        val driver = FakeRootDriver()
        val controller = RootAccessController(driver)

        assertEquals(RootRequestStatus.STARTED, controller.requestRoot())
        assertEquals(RootRequestStatus.ALREADY_REQUESTING, controller.requestRoot())
        assertEquals(requesting(), controller.status())
        driver.completeRequest(RootRequestOutcome.UNAVAILABLE)
        assertEquals(unavailable(), controller.status())
        assertEquals(0, driver.bindCount)

        assertEquals(RootRequestStatus.STARTED, controller.requestRoot())
        driver.completeRequest(RootRequestOutcome.DENIED)
        assertEquals(
            RootAccessStatus(RootGrantState.DENIED, RootServiceState.DISCONNECTED),
            controller.status(),
        )
        assertEquals(0, driver.bindCount)
    }

    @Test
    fun grantedRequestBindsServiceAndRejectsDuplicateRequest() {
        val driver = FakeRootDriver(cachedGrant = true)
        val controller = RootAccessController(driver)

        assertEquals(RootRequestStatus.STARTED, controller.requestRoot())
        driver.completeRequest(RootRequestOutcome.GRANTED)
        assertEquals(
            RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTING),
            controller.status(),
        )
        assertEquals(1, driver.bindCount)

        driver.connectService(processId = 4321)
        assertEquals(
            RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED),
            controller.status(),
        )
        assertEquals(4321, controller.rootServiceProcessIdForTest())
        assertEquals(RootRequestStatus.ALREADY_GRANTED, controller.requestRoot())
        assertEquals(1, driver.requestCount)
    }

    @Test
    fun invalidServiceIdentityFailsClosedAsLost() {
        val driver = FakeRootDriver(cachedGrant = true)
        val controller = RootAccessController(driver)

        controller.requestRoot()
        driver.completeRequest(RootRequestOutcome.GRANTED)
        driver.connectService(processId = 0)

        assertEquals(lost(), controller.status())
        assertEquals(1, driver.disconnectCount)
        assertNull(controller.rootServiceProcessIdForTest())
    }

    @Test
    fun binderLossClosesGrantedStateWithoutReplay() {
        val driver = FakeRootDriver(cachedGrant = true)
        val controller = RootAccessController(driver)

        controller.requestRoot()
        driver.completeRequest(RootRequestOutcome.GRANTED)
        driver.connectService(processId = 7654)
        driver.loseService()

        assertEquals(lost(), controller.status())
        assertEquals(1, driver.requestCount)
        assertEquals(1, driver.bindCount)
        assertEquals(1, driver.disconnectCount)
    }

    @Test
    fun revokedGrantIsDetectedByStatusAndClosesService() {
        val driver = FakeRootDriver(cachedGrant = true)
        val controller = RootAccessController(driver)

        controller.requestRoot()
        driver.completeRequest(RootRequestOutcome.GRANTED)
        driver.connectService(processId = 7654)
        driver.cachedGrant = false

        assertEquals(lost(), controller.status())
        assertEquals(1, driver.disconnectCount)
    }

    @Test
    fun userDisconnectDoesNotBecomeLostAndLateCallbackIsIgnored() {
        val driver = FakeRootDriver()
        val controller = RootAccessController(driver)

        controller.requestRoot()
        val lateCallback = driver.requestCallback
        controller.disconnect()
        lateCallback?.invoke(RootRequestOutcome.GRANTED)

        assertEquals(unavailable(), controller.status())
        assertEquals(1, driver.disconnectCount)
        assertEquals(0, driver.bindCount)
    }

    private fun unavailable() = RootAccessStatus(RootGrantState.UNAVAILABLE, RootServiceState.DISCONNECTED)

    private fun requesting() = RootAccessStatus(RootGrantState.REQUESTING, RootServiceState.DISCONNECTED)

    private fun lost() = RootAccessStatus(RootGrantState.LOST, RootServiceState.DISCONNECTED)
}

private class FakeRootDriver(
    var cachedGrant: Boolean? = null,
) : RootAccessDriver {
    var requestCount = 0
    var bindCount = 0
    var disconnectCount = 0
    var requestCallback: ((RootRequestOutcome) -> Unit)? = null
    private var connectedCallback: ((Int) -> Unit)? = null
    private var lostCallback: (() -> Unit)? = null

    override fun cachedGrant(): Boolean? = cachedGrant

    override fun requestRoot(onResult: (RootRequestOutcome) -> Unit) {
        requestCount += 1
        requestCallback = onResult
    }

    override fun bindRootService(
        onConnected: (processId: Int) -> Unit,
        onLost: () -> Unit,
    ) {
        bindCount += 1
        connectedCallback = onConnected
        lostCallback = onLost
    }

    override fun disconnect() {
        disconnectCount += 1
    }

    override fun execute(request: RootOperationRequest): RootOperationResult =
        RootOperationResult.Failed("NOT_CONFIGURED")

    fun completeRequest(outcome: RootRequestOutcome) {
        requestCallback?.invoke(outcome)
    }

    fun connectService(processId: Int) {
        connectedCallback?.invoke(processId)
    }

    fun loseService() {
        lostCallback?.invoke()
    }
}
