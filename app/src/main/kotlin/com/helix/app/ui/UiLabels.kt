package com.helix.app.ui

import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Display helpers for the HXA-028 chat UI (Chinese hardcoded; i18n is HXA-067).
 * Everything here is a pure display conversion of already-validated, persisted
 * facts — no parsing of user input (the composer normalized that), no secrets,
 * no network.
 */
object UiLabels {
    /**
     * Display form of a canonical [com.helix.core.model.NormalizedEndpoint.origin]
     * with the scheme's default port (https:443 / http:80) hidden. The canonical
     * form ALWAYS carries the port; only this display conversion drops the
     * default.
     */
    fun displayOrigin(origin: String): String {
        val sep = origin.indexOf("://")
        if (sep < 0) return origin
        val scheme = origin.substring(0, sep)
        val rest = origin.substring(sep + 3)
        val colon = rest.lastIndexOf(':')
        val port = if (colon < 0) null else rest.substring(colon + 1).toIntOrNull()
        val default =
            if (scheme == "https") {
                443
            } else if (scheme == "http") {
                80
            } else {
                -1
            }
        return if (port != null && port == default) "$scheme://${rest.substring(0, colon)}" else origin
    }

    /** User-visible data-residence label (doc 02 section 9.1 / ADR-0005). */
    fun residenceLabel(residence: ProviderResidence): String =
        when (residence) {
            ProviderResidence.ON_DEVICE_LOOPBACK -> "本机回环（数据不出设备）"
            ProviderResidence.USER_AUTHORIZED_LAN -> "局域网（已按 host:port 授权）"
            ProviderResidence.PUBLIC_CLOUD -> "公共云"
            ProviderResidence.CUSTOM_REMOTE_UNKNOWN -> "未知远程目的地"
        }

    /** User-visible protocol label. */
    fun protocolLabel(protocol: ProviderProtocol): String =
        when (protocol) {
            ProviderProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI Chat Completions"
            ProviderProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
        }

    /** Localized "MM-dd HH:mm" for session timestamps (device locale). */
    fun formatTime(millis: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
