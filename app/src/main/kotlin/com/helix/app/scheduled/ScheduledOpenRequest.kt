package com.helix.app.scheduled

import java.net.URI
import java.util.UUID

/** Untrusted navigation only. Neither this URI nor its request ID authorizes execution. */
internal data class ScheduledOpenRequest(
    val ruleId: String,
    val requestId: String,
) {
    companion object {
        fun parse(raw: String): ScheduledOpenRequest? =
            runCatching {
                require(raw.length <= 256)
                val uri = URI(raw)
                require(uri.scheme == "helix" && uri.rawAuthority == "automation")
                require(uri.rawFragment == null && uri.rawPath == uri.path)
                val rule = requireNotNull(uri.path.removePrefix("/").takeIf { "/$it" == uri.path })
                require(UUID.fromString(rule).toString() == rule)
                val query = requireNotNull(uri.rawQuery)
                require(query.startsWith("requestId="))
                val request = query.removePrefix("requestId=")
                require(UUID.fromString(request).toString() == request)
                ScheduledOpenRequest(rule, request)
            }.getOrNull()
    }
}
