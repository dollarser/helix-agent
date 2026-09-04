package com.helix.app.ui

import androidx.annotation.StringRes
import com.helix.app.R
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Display helpers for the chat UI (HXA-028). Origin/time/byte/protocol forms are
 * locale-independent ASCII; the data-residence wording is a [StringRes] resolved at the UI
 * boundary (HXA-069). Everything here is a pure display conversion of already-validated,
 * persisted facts — no parsing of user input (the composer normalized that), no secrets,
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

    /**
     * The data-residence label id (doc 02 section 9.1 / ADR-0005). Returns a [StringRes] so the
     * user-visible wording lives in resources (HXA-069); callers wrap it in `stringResource(...)`.
     */
    @StringRes
    fun residenceLabelRes(residence: ProviderResidence): Int =
        when (residence) {
            ProviderResidence.ON_DEVICE_LOOPBACK -> R.string.ui_residence_on_device_loopback
            ProviderResidence.USER_AUTHORIZED_LAN -> R.string.ui_residence_user_authorized_lan
            ProviderResidence.PUBLIC_CLOUD -> R.string.ui_residence_public_cloud
            ProviderResidence.CUSTOM_REMOTE_UNKNOWN -> R.string.ui_residence_custom_remote_unknown
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

    /** Human-readable byte size for a staged attachment (B / KiB / MiB), no decimals at large units. */
    fun formatBytes(bytes: Long): String {
        val kib = 1024.0
        val mib = kib * 1024.0
        return when {
            bytes < 0 -> {
                "0 B"
            }

            bytes < 1024 -> {
                "$bytes B"
            }

            bytes < mib -> {
                "${(bytes / kib).toInt()} KiB"
            }

            else -> {
                val value = bytes / mib
                if (value == value.toLong().toDouble()) {
                    "${value.toLong()} MiB"
                } else {
                    String.format(Locale.getDefault(), "%.1f MiB", value)
                }
            }
        }
    }
}
