package com.helix.app.companions

/** Exact signer matching preserves the existing signature-protected companion boundary. */
internal object RuntimeApkPolicy {
    fun accepts(
        expectedPackage: String,
        actualPackage: String,
        hostSigners: Set<String>,
        apkSigners: Set<String>,
    ): Boolean = actualPackage == expectedPackage && hostSigners.isNotEmpty() && apkSigners == hostSigners
}
