package com.helix.app.connector

/** UI intent only; an inspected or registered identity still passes prepareOAuth binding checks. */
internal data class OAuthSetupConnection(
    val request: Int = 0,
    val advanced: Boolean = true,
    val onBusy: (Boolean) -> Unit = {},
    val launch: (String) -> Unit = {},
) {
    fun continueLogin(clientId: String) {
        if (request > 0) launch(clientId)
    }
}
