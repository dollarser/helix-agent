package com.helix.extensions.skills

/** Facts supplied by the installed host; absent facts must never be guessed by bundled help. */
data class HelixHelpIdentity(
    val versionName: String? = null,
    val versionCode: Long? = null,
    val distribution: String? = null,
    val applicationId: String? = null,
    val debuggable: Boolean? = null,
)
