package com.helix.runtime.proot.core

/**
 * The installed-copy record (`manifest.json` under `runtime/<install-id>/`, architecture
 * doc section 6.3).
 *
 * The manifest EMBEDS the full [lock] that produced the install and records
 * [lockSha256] = SHA-256 of the lock's canonical encoding: parse-time integrity
 * ([RuntimeInstallManifestCodec.parse]) recomputes the fingerprint and rejects any
 * tampered embedded lock, so the installed manifest always proves which exact version
 * truth produced it (HXA-082 activation gate).
 */
data class RuntimeInstallManifest(
    val schemaVersion: Int,
    val installId: String,
    val abi: RuntimeAbi,
    val installedAtEpochMs: Long,
    val lockSha256: String,
    val lock: RuntimeLock,
    val smoke: RuntimeSmokeResult?,
) {
    init {
        require(abi == lock.abi) { "manifest ABI ${abi.wire} != embedded lock ABI ${lock.abi.wire}" }
        require(lockSha256 == RuntimeLockCodec.sha256Hex(lock)) {
            "manifest.lockSha256 does not match the embedded lock"
        }
    }
}

/** Result of the post-install smoke run recorded in [RuntimeInstallManifest]. */
data class RuntimeSmokeResult(
    val status: SmokeStatus,
    val checkedAtEpochMs: Long,
    val failures: List<String>,
) {
    init {
        val expectedFailuresEmpty = status == SmokeStatus.PASSED
        require(expectedFailuresEmpty == failures.isEmpty()) {
            "smoke $status must ${if (expectedFailuresEmpty) "have no failures" else "list at least one failure"}"
        }
    }
}

enum class SmokeStatus(
    val wire: String,
) {
    PASSED("PASSED"),
    FAILED("FAILED"),
    ;

    companion object {
        fun fromWire(wire: String): SmokeStatus =
            entries.firstOrNull { it.wire == wire }
                ?: throw RuntimeLockSchemaException("unknown smoke status: $wire")
    }
}

/**
 * The activation pointer (`state/active.json` / `state/rollback.json`, architecture doc
 * section 6.3): which install id is currently active (or kept as the one-version rollback,
 * HXA-082 保留一版 rollback). Both files share this schema.
 */
data class RuntimeActivation(
    val schemaVersion: Int,
    val installId: String,
    val activatedAtEpochMs: Long,
)
