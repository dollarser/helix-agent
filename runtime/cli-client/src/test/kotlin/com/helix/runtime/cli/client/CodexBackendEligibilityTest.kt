package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexBackendEligibilityTest {
    @Test
    fun verifiedLinuxArtifactStillFailsClosedWithoutAndroidVendorSupport() {
        val result =
            CodexBackendEligibility.assess(
                artifactVerified = true,
                vendorSupportsAndroid = false,
                deviceProbePassed = true,
            )

        assertEquals(CodexBackendEligibility.UNSUPPORTED_ANDROID_PLATFORM, result)
        assertFalse(result.mayPackage)
    }

    @Test
    fun artifactVerificationPrecedesPlatformAndProbeClaims() {
        val result =
            CodexBackendEligibility.assess(
                artifactVerified = false,
                vendorSupportsAndroid = true,
                deviceProbePassed = true,
            )

        assertEquals(CodexBackendEligibility.ARTIFACT_UNVERIFIED, result)
        assertFalse(result.mayPackage)
    }

    @Test
    fun failedDeviceProbeCannotPackageEvenOnSupportedPlatform() {
        val result =
            CodexBackendEligibility.assess(
                artifactVerified = true,
                vendorSupportsAndroid = true,
                deviceProbePassed = false,
            )

        assertEquals(CodexBackendEligibility.DEVICE_PROBE_FAILED, result)
        assertFalse(result.mayPackage)
    }

    @Test
    fun packagingRequiresAllThreeIndependentGates() {
        val result =
            CodexBackendEligibility.assess(
                artifactVerified = true,
                vendorSupportsAndroid = true,
                deviceProbePassed = true,
            )

        assertTrue(result.mayPackage)
    }
}
