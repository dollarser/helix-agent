package com.helix.core.policy

import com.helix.core.model.ExecutionTargetType
import java.security.MessageDigest

/**
 * The exact fact set one approval authorizes (roadmap HXA-034; architecture doc section 9.2):
 * the tool identity (name/version), the registered schema hash, the user scope, the session,
 * the execution target, the presenting UI token, and the canonical arguments hash.
 *
 * Every field is a trusted execution-path fact — none of them is model-declared. An approval
 * for a different tool version, schema, scope, session, target, UI token or argument set is a
 * different binding with a different hash: replaying an approval hash to any other of these
 * values fails (security doc section 7.3).
 *
 * The profile (STANDARD/ADVANCED), Android permission state, runtime installs and Root grants
 * are deliberately NOT part of the binding: changing any of them never changes an existing
 * approval decision and never mints a general credential (security doc section 7.3).
 */
data class ApprovalBinding(
    val toolCallId: String,
    val toolName: String,
    val toolVersion: String,
    val schemaHash: String,
    val scopeRef: String,
    val sessionId: String,
    val executionTarget: ExecutionTargetType,
    val uiToken: String,
    val argsHash: String,
) {
    init {
        require(toolCallId.length in 1..64) { "toolCallId must be 1..64 chars" }
        require(toolName.length in 1..128) { "toolName must be 1..128 chars" }
        require(toolVersion.length in 1..32) { "toolVersion must be 1..32 chars" }
        require(isSha256Hex(schemaHash)) { "schemaHash must be a sha256 hex string" }
        require(scopeRef.length in 1..MAX_SCOPE_REF_LENGTH) { "scopeRef must be 1..$MAX_SCOPE_REF_LENGTH chars" }
        require(sessionId.length in 1..64) { "sessionId must be 1..64 chars" }
        require(uiToken.length in 1..128) { "uiToken must be 1..128 chars" }
        require(isSha256Hex(argsHash)) { "argsHash must be a sha256 hex string" }
    }

    /**
     * Canonical JSON: fixed alphabetically sorted keys, no whitespace, full string escaping.
     * Deterministic for equal bindings — the hash of two equal bindings is identical.
     */
    val canonicalJson: String
        get() =
            buildString {
                append("{\"argsHash\":\"")
                append(escape(argsHash))
                append("\",\"executionTarget\":\"")
                append(escape(executionTarget.name))
                append("\",\"scopeRef\":\"")
                append(escape(scopeRef))
                append("\",\"schemaHash\":\"")
                append(escape(schemaHash))
                append("\",\"sessionId\":\"")
                append(escape(sessionId))
                append("\",\"toolCallId\":\"")
                append(escape(toolCallId))
                append("\",\"toolName\":\"")
                append(escape(toolName))
                append("\",\"toolVersion\":\"")
                append(escape(toolVersion))
                append("\",\"uiToken\":\"")
                append(escape(uiToken))
                append("\"}")
            }

    /** The approval hash: SHA-256 of [canonicalJson], hex. Stored in the approvals table. */
    val hash: String
        get() = sha256Hex(canonicalJson)

    companion object {
        /**
         * Same bound as UserScope scope refs (HXA-032): refs that do not fit the audit column
         * are structurally invalid.
         */
        const val MAX_SCOPE_REF_LENGTH = 1024

        internal fun isSha256Hex(value: String): Boolean =
            value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

        internal fun escape(value: String): String =
            buildString {
                for (c in value) {
                    when {
                        c == '\\' -> append("\\\\")
                        c == '"' -> append("\\\"")
                        c < ' ' -> append("\\u%04x".format(c.code))
                        else -> append(c)
                    }
                }
            }

        private fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val out = StringBuilder(digest.size * 2)
            for (b in digest) out.append("%02x".format(b))
            return out.toString()
        }
    }
}
