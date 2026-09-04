package com.helix.tools.automation

import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
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
)

data class AutomationSnapshot(
    val packageName: String,
    val windowId: Int,
    val generation: Long,
    val createdAt: Instant,
    val nodes: List<AutomationSnapshotNode>,
    val truncated: Boolean,
)

data class AutomationSnapshotResult(
    val status: AutomationSnapshotStatus,
    val snapshot: AutomationSnapshot? = null,
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
    EXPIRED,
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
    EXPIRED,
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
    private val clock: Clock = SystemClock(),
    private val tokenBytes: () -> ByteArray = {
        ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes)
    },
) {
    private data class Entry(
        val binding: NodeTokenBinding,
        val expiresAt: Instant,
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun beginSnapshot() {
        entries.clear()
    }

    @Synchronized
    fun issue(binding: NodeTokenBinding): String {
        check(entries.size < MAX_TOKENS) { "snapshot token budget exceeded" }
        val token = tokenBytes().toHex()
        check(token.length == TOKEN_BYTES * 2 && token !in entries) { "invalid or duplicate token" }
        entries[token] = Entry(binding, clock.now().plus(TOKEN_TTL))
        return token
    }

    @Synchronized
    fun lookup(token: String): NodeTokenLookup {
        val entry = entries[token] ?: return NodeTokenLookup(NodeTokenLookupStatus.UNKNOWN)
        return if (!clock.now().isBefore(entry.expiresAt)) {
            entries.remove(token)
            NodeTokenLookup(NodeTokenLookupStatus.EXPIRED)
        } else {
            NodeTokenLookup(NodeTokenLookupStatus.VALID, entry.binding)
        }
    }

    @Synchronized
    @Suppress("ReturnCount")
    fun resolve(
        token: String,
        packageName: String,
        windowId: Int,
        generation: Long,
        fingerprint: String,
    ): NodeTokenResolution {
        val entry = entries[token] ?: return NodeTokenResolution(NodeTokenResolutionStatus.UNKNOWN)
        val now = clock.now()
        if (!now.isBefore(entry.expiresAt)) {
            entries.remove(token)
            return NodeTokenResolution(NodeTokenResolutionStatus.EXPIRED)
        }
        val binding = entry.binding
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

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    companion object {
        private const val TOKEN_BYTES = 16
        const val MAX_TOKENS = 200
        val TOKEN_TTL: Duration = Duration.ofSeconds(30)
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
