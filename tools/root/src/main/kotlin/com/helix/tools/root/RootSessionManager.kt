package com.helix.tools.root

import com.helix.core.model.Clock
import com.helix.core.policy.RootSessionScope
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant

enum class RootSessionState { INACTIVE, ACTIVE, EXPIRED, LOST }

data class RootSessionStatus(
    val state: RootSessionState,
    val scope: RootSessionScope?,
    val rootScopeIds: Set<String>,
)

data class ResolvedRootPath(
    val root: String,
    val path: String,
)

/**
 * One explicitly user-started RootSession. The idle deadline slides after a successful
 * high-level operation but never exceeds the one-hour type-level hard cap. Clock rollback,
 * Root grant loss and RootService loss all close the session fail-closed.
 */
class RootSessionManager(
    private val clock: Clock,
    private val accessStatus: () -> RootAccessStatus,
    private val onClose: () -> Unit,
) {
    private var state = RootSessionState.INACTIVE
    private var startedAt: Instant? = null
    private var lastActivityAt: Instant? = null
    private var roots: Map<String, Path> = emptyMap()

    @Synchronized
    fun start(selectedRoots: Map<String, String> = emptyMap()): RootSessionStatus {
        val access = accessStatus()
        require(access.grant == RootGrantState.GRANTED && access.service == RootServiceState.CONNECTED) {
            "ROOT_NOT_CONNECTED"
        }
        val now = clock.now()
        roots = selectedRoots.mapValues { (id, raw) -> validateRoot(id, raw) }
        state = RootSessionState.ACTIVE
        startedAt = now
        lastActivityAt = now
        return statusLocked(now)
    }

    @Synchronized
    fun status(): RootSessionStatus = statusLocked(clock.now())

    @Synchronized
    fun requireActive(): RootSessionScope {
        val status = statusLocked(clock.now())
        check(status.state == RootSessionState.ACTIVE) { "ROOT_SESSION_${status.state.name}" }
        return requireNotNull(status.scope)
    }

    /** Resolves only a user-selected root plus a relative path; raw absolute paths are never model inputs. */
    @Synchronized
    fun resolve(
        scopeId: String,
        relativePath: String,
    ): ResolvedRootPath {
        requireActive()
        require(relativePath.length in 1..MAX_RELATIVE_PATH_LENGTH) { "ROOT_PATH_INVALID" }
        require(!relativePath.startsWith('/')) { "ROOT_PATH_MUST_BE_RELATIVE" }
        val root = roots[scopeId] ?: throw IllegalArgumentException("ROOT_SCOPE_UNKNOWN")
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root) && resolved != root) { "ROOT_PATH_ESCAPES_SCOPE" }
        requireNotSensitive(resolved)
        return ResolvedRootPath(root.toString(), resolved.toString())
    }

    @Synchronized
    fun recordSuccessfulActivity() {
        requireActive()
        lastActivityAt = clock.now()
    }

    @Synchronized
    fun close() {
        state = RootSessionState.INACTIVE
        startedAt = null
        lastActivityAt = null
        roots = emptyMap()
        onClose()
    }

    @Synchronized
    private fun statusLocked(now: Instant): RootSessionStatus {
        if (state == RootSessionState.ACTIVE) {
            val access = accessStatus()
            if (access.grant != RootGrantState.GRANTED || access.service != RootServiceState.CONNECTED) {
                expire(RootSessionState.LOST)
            } else {
                val start = requireNotNull(startedAt)
                val last = requireNotNull(lastActivityAt)
                val clockRolledBack = now.isBefore(start) || now.isBefore(last)
                val hardEnd = start.plus(MAX_SESSION_DURATION)
                val idleEnd = last.plus(IDLE_TIMEOUT)
                if (clockRolledBack || !now.isBefore(minOf(hardEnd, idleEnd))) {
                    expire(RootSessionState.EXPIRED)
                }
            }
        }
        val scope =
            if (state == RootSessionState.ACTIVE) {
                val start = requireNotNull(startedAt)
                val expiry = minOf(start.plus(MAX_SESSION_DURATION), requireNotNull(lastActivityAt).plus(IDLE_TIMEOUT))
                RootSessionScope(start, expiry, highLevelToolsOnly = true)
            } else {
                null
            }
        return RootSessionStatus(state, scope, roots.keys.toSortedSet())
    }

    private fun expire(terminal: RootSessionState) {
        state = terminal
        roots = emptyMap()
        onClose()
    }

    private fun validateRoot(
        id: String,
        raw: String,
    ): Path {
        require(SCOPE_ID.matches(id)) { "ROOT_SCOPE_ID_INVALID" }
        require(raw.length in 1..MAX_ROOT_PATH_LENGTH && raw.startsWith('/')) { "ROOT_SCOPE_PATH_INVALID" }
        val root = Paths.get(raw).normalize()
        require(root.isAbsolute && root.toString() != "/") { "ROOT_SCOPE_TOO_BROAD" }
        requireNotSensitive(root)
        return root
    }

    private fun requireNotSensitive(path: Path) {
        val value = path.normalize().toString()
        require(SENSITIVE_PREFIXES.none { value == it || value.startsWith("$it/") }) {
            "ROOT_PATH_SENSITIVE"
        }
    }

    companion object {
        val IDLE_TIMEOUT: Duration = Duration.ofMinutes(10)
        val MAX_SESSION_DURATION: Duration = Duration.ofMinutes(60)
        const val MAX_RELATIVE_PATH_LENGTH = 1024
        const val MAX_ROOT_PATH_LENGTH = 1024
        private val SCOPE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val SENSITIVE_PREFIXES =
            setOf(
                "/data/data",
                "/data/user",
                "/data/misc/keystore",
                "/data/system/locksettings.db",
                "/proc/kcore",
                "/proc/keys",
                "/proc/sysrq-trigger",
                "/dev",
            )
    }
}
