package com.helix.tools.automation

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

enum class AutomationSnapshotStatus {
    SUCCESS,
    SERVICE_NOT_CONNECTED,
    NO_ACTIVE_SESSION,
    TARGET_NOT_ALLOWLISTED,
    TARGET_CHANGED,
    SENSITIVE_UI,
    UNSUPPORTED_UI,
}

data class AutomationNodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class AutomationNodeRange(
    val min: Float,
    val max: Float,
    val current: Float,
) {
    fun accepts(value: Double): Boolean =
        min.isFinite() && max.isFinite() && current.isFinite() &&
            min <= max && current in min..max && value.isFinite() && value >= min && value <= max
}

data class AutomationSnapshotNode(
    val token: String,
    val parentToken: String?,
    val depth: Int,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: AutomationNodeBounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val range: AutomationNodeRange? = null,
    val canSetProgress: Boolean = false,
    val redacted: Boolean = false,
    val canImeEnter: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
)

data class AutomationSnapshot(
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val createdAt: Instant,
    val nodes: List<AutomationSnapshotNode>,
    val truncated: Boolean,
    val truncationReasons: Set<String> = emptySet(),
)

data class AutomationSnapshotResult(
    val status: AutomationSnapshotStatus,
    val snapshot: AutomationSnapshot? = null,
    val pauseReason: AutomationPauseReason? = null,
    val targetPackage: String? = null,
    val backend: String? = null,
)

internal data class NodeTokenBinding(
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val fingerprint: String,
    val path: List<Int>,
)

internal enum class NodeTokenResolutionStatus {
    VALID,
    UNKNOWN,
    PACKAGE_MISMATCH,
    WINDOW_MISMATCH,
    GENERATION_MISMATCH,
    FINGERPRINT_MISMATCH,
}

internal data class NodeTokenResolution(
    val status: NodeTokenResolutionStatus,
    val binding: NodeTokenBinding? = null,
)

internal enum class NodeTokenLookupStatus {
    VALID,
    UNKNOWN,
}

internal data class NodeTokenLookup(
    val status: NodeTokenLookupStatus,
    val binding: NodeTokenBinding? = null,
)

/**
 * Opaque, process-local node tokens. The registry never retains AccessibilityNodeInfo instances;
 * actions must reacquire the root and re-walk [NodeTokenBinding.path] before trusting a token.
 */
internal class NodeTokenRegistry(
    private val tokenBytes: () -> ByteArray = { ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes) },
) {
    private val entries = LinkedHashMap<String, NodeTokenBinding>()

    @Synchronized
    fun beginSnapshot() {
        entries.clear()
    }

    @Synchronized
    fun issue(binding: NodeTokenBinding): String {
        check(entries.size < MAX_TOKENS) { "snapshot token budget exceeded" }
        val token = tokenBytes().joinToString(separator = "") { "%02x".format(it) }
        check(token.length == TOKEN_BYTES * 2 && token !in entries) { "invalid or duplicate token" }
        entries[token] = binding
        return token
    }

    @Synchronized
    fun lookup(token: String): NodeTokenLookup =
        entries[token]?.let { NodeTokenLookup(NodeTokenLookupStatus.VALID, it) }
            ?: NodeTokenLookup(NodeTokenLookupStatus.UNKNOWN)

    @Synchronized
    fun resolve(
        token: String,
        packageName: String,
        windowId: Int,
        generation: Long,
        fingerprint: String,
    ): NodeTokenResolution {
        val binding = entries[token] ?: return NodeTokenResolution(NodeTokenResolutionStatus.UNKNOWN)
        val status =
            when {
                binding.packageName != packageName -> NodeTokenResolutionStatus.PACKAGE_MISMATCH
                binding.windowId != windowId -> NodeTokenResolutionStatus.WINDOW_MISMATCH
                binding.generation != generation -> NodeTokenResolutionStatus.GENERATION_MISMATCH
                binding.fingerprint != fingerprint -> NodeTokenResolutionStatus.FINGERPRINT_MISMATCH
                else -> NodeTokenResolutionStatus.VALID
            }
        return NodeTokenResolution(status, binding.takeIf { status == NodeTokenResolutionStatus.VALID })
    }

    @Synchronized
    fun invalidate() {
        entries.clear()
    }

    companion object {
        private const val TOKEN_BYTES = 16
        const val MAX_TOKENS = 200
    }
}

internal fun nodeFingerprint(canonicalFields: List<String?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    canonicalFields.forEach { field ->
        val encoded = field.orEmpty().toByteArray(Charsets.UTF_8)
        digest.update((encoded.size ushr 24).toByte())
        digest.update((encoded.size ushr 16).toByte())
        digest.update((encoded.size ushr 8).toByte())
        digest.update(encoded.size.toByte())
        digest.update(encoded)
    }
    return digest
        .digest()
        .take(16)
        .toByteArray()
        .joinToString(separator = "") { "%02x".format(it) }
}
