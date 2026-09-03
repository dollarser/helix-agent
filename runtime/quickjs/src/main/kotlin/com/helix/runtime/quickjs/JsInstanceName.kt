package com.helix.runtime.quickjs

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * HXA-051 isolated-service instance naming (roadmap M5 / doc 03 §4.5).
 *
 * Every execution binds a unique `bindIsolatedService` instance whose name is
 * `js_` + 32 lowercase hex digits — the first 128 bits of the SHA-256 of the
 * execution ID. Derivation (rather than any caller-supplied text) guarantees the name
 * contains only Android-allowed characters (`[a-z0-9_]`), has a fixed length of 35, and is
 * deterministic per execution ID. The instance name is the protocol's instance key — the
 * actual Linux process name is an OS detail and is never used as an ID or a security
 * boundary (doc 03 §2.2).
 */
object JsInstanceName {
    const val PREFIX: String = "js_"

    const val HEX_DIGITS: Int = 32

    const val TOTAL_LENGTH: Int = PREFIX.length + HEX_DIGITS

    private val FORMAT = Regex("js_[0-9a-f]{32}")

    /** Derives the unique instance name for an execution ID (deterministic, total). */
    fun forExecution(executionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(executionId.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.take(HEX_DIGITS / 2).joinToString("") { "%02x".format(it) }
        return PREFIX + hex
    }

    /** Validates the fixed form: `js_` + 32 lowercase hex digits, length 35. */
    fun isValid(instanceName: String): Boolean = instanceName.matches(FORMAT)
}
