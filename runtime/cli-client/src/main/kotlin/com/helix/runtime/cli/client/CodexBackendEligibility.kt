package com.helix.runtime.cli.client

/** Fail-closed result of the HXA-111 execution-base gate. */
enum class CodexBackendEligibility {
    VENDOR_SUPPORTED_ANDROID,
    UNSUPPORTED_ANDROID_PLATFORM,
    DEVICE_PROBE_FAILED,
    ARTIFACT_UNVERIFIED,
    ;

    val mayPackage: Boolean
        get() = this == VENDOR_SUPPORTED_ANDROID

    companion object {
        fun assess(
            artifactVerified: Boolean,
            vendorSupportsAndroid: Boolean,
            deviceProbePassed: Boolean,
        ): CodexBackendEligibility =
            when {
                !artifactVerified -> ARTIFACT_UNVERIFIED
                !vendorSupportsAndroid -> UNSUPPORTED_ANDROID_PLATFORM
                !deviceProbePassed -> DEVICE_PROBE_FAILED
                else -> VENDOR_SUPPORTED_ANDROID
            }
    }
}
