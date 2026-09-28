package com.helix.app.localmodel

import com.helix.provider.api.ProbeOutcome

enum class LocalModelTransferPhase {
    DOWNLOADING,
    VERIFYING,
    READY,
}

data class LocalModelTransferProgress(
    val phase: LocalModelTransferPhase,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

data class LocalModelInstallResult(
    val providerId: String,
    val modelId: String,
    val connection: ProbeOutcome,
    val capabilities: ProbeOutcome?,
)

internal object LocalModelInstallSpace {
    const val FREE_SPACE_RESERVE_BYTES = 16L * 1024 * 1024

    /** Transfer partial and verified store publish overlap on the same app-private volume. */
    fun additionalBytesRequired(
        sizeBytes: Long,
        partialBytes: Long,
    ): Long {
        require(sizeBytes > 0 && partialBytes in 0..sizeBytes)
        return (sizeBytes - partialBytes) + sizeBytes + FREE_SPACE_RESERVE_BYTES
    }
}

internal data class LocalModelDownloadPolicy(
    val allowHttp: Boolean = false,
    val redirectHostSuffixes: Set<String> = emptySet(),
) {
    fun validateInitial(uri: java.net.URI) {
        require(uri.host != null && uri.userInfo == null && uri.fragment == null)
        require(uri.scheme == "https" || (allowHttp && uri.scheme == "http")) { "Use an HTTPS model source" }
    }

    fun validateRedirect(uri: java.net.URI) {
        require(redirectHostSuffixes.isNotEmpty()) { "Direct model URLs must not redirect" }
        validateInitial(uri)
        val host = requireNotNull(uri.host).lowercase()
        require(redirectHostSuffixes.any { suffix -> host == suffix || host.endsWith(".$suffix") }) {
            "Model source redirected outside its trusted host family"
        }
    }
}
