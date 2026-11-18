package com.helix.app.ui

import com.helix.app.account.AccountCaptchaAction
import com.helix.app.account.AccountCaptchaProof
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/** One dialog owns one proof. No URLs, messages or proof values are logged or persisted. */
internal class AccountCaptchaPolicy(
    private val action: AccountCaptchaAction,
) {
    private val closed = AtomicBoolean(false)
    val url = "$ORIGIN/auth/captcha?action=${action.wireValue}"

    fun close() {
        closed.set(true)
    }

    @Suppress("ReturnCount") // Reject each untrusted cancellation boundary before closing the one-shot dialog.
    fun receiveCancellation(
        origin: String,
        mainFrame: Boolean,
        currentUrl: String?,
        message: String?,
    ): Boolean {
        if (closed.get() || !mainFrame || !trustedOrigin(origin)) return false
        if (currentUrl != url) return false
        if (message == null || message.length > 4096) return false
        val body = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return false
        if (body["type"] != JsonPrimitive("captcha-cancelled") ||
            body["action"] != JsonPrimitive(action.wireValue)
        ) {
            return false
        }
        return closed.compareAndSet(false, true)
    }

    @Suppress("ReturnCount") // Explicit fail-closed message boundaries precede single-use delivery.
    fun receive(
        origin: String,
        mainFrame: Boolean,
        currentUrl: String?,
        message: String?,
    ): AccountCaptchaProof? {
        if (closed.get() || !mainFrame || !trustedOrigin(origin)) return null
        if (currentUrl != url || message == null || message.length > 4096) return null
        val body = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return null

        fun string(key: String) = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (string("type") != "captcha-completed" || string("action") != action.wireValue) return null
        val token = string("token")?.takeIf { it.isNotBlank() && it.length <= 2048 } ?: return null
        if (!closed.compareAndSet(false, true)) return null
        return AccountCaptchaProof(action, token)
    }

    fun navigationAllowed(
        url: String,
        mainFrame: Boolean,
    ): Boolean = if (mainFrame) url == this.url else resourceAllowed(url)

    companion object {
        const val ORIGIN = "https://accounts.hilex.me"

        fun trustedOrigin(value: String): Boolean = trustedHost(value) == "accounts.hilex.me"

        fun resourceAllowed(value: String): Boolean =
            value in setOf("about:blank", "about:srcdoc") ||
                trustedHost(value) in setOf("accounts.hilex.me", "challenges.cloudflare.com")

        private fun trustedHost(value: String): String? =
            runCatching {
                URI(value)
                    .takeIf {
                        it.scheme == "https" && it.port in
                            listOf(
                                -1,
                                443,
                            ) && it.rawUserInfo == null
                    }?.host
            }.getOrNull()
    }
}
