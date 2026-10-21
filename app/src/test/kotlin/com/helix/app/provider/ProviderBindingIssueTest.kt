package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBindingIssueTest {
    private val capabilities =
        ProviderCapabilities(true, true, false, false, true, false, 8192, CapabilitySource.PROBED)
    private val passed = ConnectionTestStatus.Passed(1, capabilities)

    @Test fun deletedSourceWinsOverStalePassedEvidence() {
        assertEquals(ProviderBindingIssue.SOURCE_DELETED, providerBindingIssue(false, false, passed))
        assertEquals(
            ProviderBindingIssue.SOURCE_DELETED,
            providerBindingIssue(false, true, ConnectionTestStatus.Untested),
        )
    }

    @Test fun updatedConfigurationAndNeverTestedHaveDifferentReasons() {
        assertEquals(
            ProviderBindingIssue.CONFIGURATION_CHANGED,
            providerBindingIssue(true, true, ConnectionTestStatus.Untested),
        )
        assertEquals(ProviderBindingIssue.UNTESTED, providerBindingIssue(true, false, ConnectionTestStatus.Untested))
    }

    @Test fun authenticationNetworkAndOtherFailuresAreDistinct() {
        fun failure(code: ModelErrorCode) =
            providerBindingIssue(true, false, ConnectionTestStatus.Failed(1, 1, code, false))
        assertEquals(ProviderBindingIssue.AUTHENTICATION, failure(ModelErrorCode.AUTH))
        assertEquals(ProviderBindingIssue.NETWORK, failure(ModelErrorCode.TRANSPORT))
        assertEquals(ProviderBindingIssue.NETWORK, failure(ModelErrorCode.TIMEOUT))
        // HTTP 404 alone could be a missing route, so it must not be called a missing model.
        assertEquals(ProviderBindingIssue.TEST_FAILED, failure(ModelErrorCode.HTTP_ERROR))
    }

    @Test fun expiredManagedAccountBlocksEvenWithPassedTest() {
        assertEquals(
            ProviderBindingIssue.AUTHENTICATION,
            providerBindingIssue(
                true,
                false,
                passed,
                ManagedAccountSnapshot(ManagedAccountSnapshot.State.CREDENTIAL_ERROR),
            ),
        )
        assertEquals(
            ProviderBindingIssue.NETWORK,
            providerBindingIssue(
                true,
                false,
                passed,
                ManagedAccountSnapshot(ManagedAccountSnapshot.State.UNAVAILABLE),
            ),
        )
    }

    @Test fun validBindingSurvivesDisplayChangesAndDoesNotDependOnPickerMembership() {
        assertNull(providerBindingIssue(true, false, passed))
        assertNull(providerBindingIssue(true, true, passed))
        assertEquals(
            ProviderBindingIssue.AUTHENTICATION,
            providerBindingIssue(true, false, passed, modelFailure = ModelErrorCode.AUTH),
        )
    }

    @Test fun onlyFreshSuccessfulAuthoritativeDirectoryCanRetireBuiltInModel() {
        val catalog = BuiltInModelCatalog(100, listOf("new"))
        assertEquals(ProviderBindingIssue.MODEL_MISSING, builtInBindingIssue(true, catalog, "old", 101))
        assertNull(builtInBindingIssue(true, catalog, "new", 101))
        assertNull(builtInBindingIssue(true, catalog.copy(failure = ModelErrorCode.TRANSPORT), "old", 101))
        assertNull(builtInBindingIssue(true, catalog, "old", BuiltInModelCatalog.SUCCESS_TTL + 100))
        assertEquals(ProviderBindingIssue.AUTHENTICATION, builtInBindingIssue(false, catalog, "new", 101))
    }

    @Test fun changedMarkerSurvivesRestartAndClearsOnlyWithNewEvidenceOrDeletion() {
        val backing = InMemoryLineStore()
        val first = ProviderTestStatusStore(backing)
        first.recordPassed("p", 1, capabilities)
        first.clear("p", configurationChanged = true)
        val reopened = ProviderTestStatusStore(backing)
        assertTrue("p" in reopened.changedConfigurations)
        assertEquals(ConnectionTestStatus.Untested, reopened.statusFor("p"))
        assertFalse("other" in reopened.changedConfigurations)
        reopened.recordFailed("p", 2, 1, ModelErrorCode.AUTH, false)
        assertFalse("p" in reopened.changedConfigurations)
        reopened.clear("p", configurationChanged = true)
        reopened.recordPassed("p", 3, capabilities)
        assertNull(providerBindingIssue(true, "p" in reopened.changedConfigurations, reopened.statusFor("p")))
        reopened.clear("p", configurationChanged = true)
        reopened.clear("p")
        assertFalse("p" in ProviderTestStatusStore(backing).changedConfigurations)
    }
}
