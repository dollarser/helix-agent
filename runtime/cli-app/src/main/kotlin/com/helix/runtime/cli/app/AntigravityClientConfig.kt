package com.helix.runtime.cli.app

/** Build-time public-client parameters are extractable from APKs; never use a confidential client. */
internal class AntigravityClientConfig(
    val id: String,
    val secret: String,
) {
    val configured: Boolean get() = id.isNotBlank() && secret.isNotBlank()

    fun requireConfigured() {
        if (!configured) throw AntigravityClientNotConfigured()
    }

    override fun toString(): String = "AntigravityClientConfig(configured=$configured)"

    companion object {
        fun build() = AntigravityClientConfig(BuildConfig.ANTIGRAVITY_CLIENT_ID, BuildConfig.ANTIGRAVITY_CLIENT_SECRET)
    }
}

internal class AntigravityClientNotConfigured :
    IllegalStateException("Antigravity OAuth is not configured in this build")
