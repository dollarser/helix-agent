package com.helix.tools.automation

import com.helix.core.model.Clock
import com.helix.core.policy.AutomationSessionScope
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Why an active Accessibility automation session ended. */
enum class AutomationStopReason {
    USER_STOP,
    EXPIRED,
    CLOCK_ROLLBACK,
    SERVICE_INTERRUPTED,
    SERVICE_DISCONNECTED,
    ALLOWLIST_CHANGED,
    FOREGROUND_START_FAILED,
}

/** Stable start outcomes used by the permission center; none of them starts an Agent Tool. */
enum class AutomationSessionStartStatus {
    STARTED,
    SERVICE_NOT_CONNECTED,
    SESSION_ALREADY_ACTIVE,
    EMPTY_TARGETS,
    INVALID_PACKAGE,
    TARGET_NOT_ALLOWLISTED,
    INVALID_TTL,
}

data class ActiveAutomationSession(
    val id: String,
    val startedAt: Instant,
    val scope: AutomationSessionScope,
)

data class AutomationSessionStartResult(
    val status: AutomationSessionStartStatus,
    val session: ActiveAutomationSession? = null,
)

/**
 * Process-local, single-session lifecycle for HXA-090. The default and current hard maximum are
 * five minutes and 30 actions. HXA-093 may add Advanced budget choices inside a release hard cap;
 * until then callers cannot widen either value. A clock rollback, expiry, allowlist reduction, or
 * service loss closes the session rather than preserving a stale grant.
 */
class AutomationSessionManager(
    private val clock: Clock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private var active: ActiveAutomationSession? = null

    @Volatile
    var lastStopReason: AutomationStopReason? = null
        private set

    @Synchronized
    fun start(
        requestedPackages: Set<String>,
        persistedAllowlist: Set<String>,
        ttl: Duration = DEFAULT_TTL,
    ): AutomationSessionStartResult {
        expireIfNeeded(clock.now())
        val refusal =
            when {
                active != null -> {
                    AutomationSessionStartStatus.SESSION_ALREADY_ACTIVE
                }

                requestedPackages.isEmpty() -> {
                    AutomationSessionStartStatus.EMPTY_TARGETS
                }

                !requestedPackages.all(AndroidPackageName::isValid) -> {
                    AutomationSessionStartStatus.INVALID_PACKAGE
                }

                !persistedAllowlist.containsAll(requestedPackages) -> {
                    AutomationSessionStartStatus.TARGET_NOT_ALLOWLISTED
                }

                ttl.isZero || ttl.isNegative || ttl > MAX_TTL -> {
                    AutomationSessionStartStatus.INVALID_TTL
                }

                else -> {
                    null
                }
            }
        if (refusal != null) return result(refusal)

        val now = clock.now()
        val session =
            ActiveAutomationSession(
                id = idFactory(),
                startedAt = now,
                scope =
                    AutomationSessionScope(
                        allowedPackages = requestedPackages.toSet(),
                        deniedPackages = emptySet(),
                        maxActions = DEFAULT_MAX_ACTIONS,
                        expiresAt = now.plus(ttl),
                    ),
            )
        active = session
        lastStopReason = null
        return AutomationSessionStartResult(AutomationSessionStartStatus.STARTED, session)
    }

    @Synchronized
    fun current(): ActiveAutomationSession? {
        expireIfNeeded(clock.now())
        return active
    }

    @Synchronized
    fun stop(reason: AutomationStopReason): Boolean {
        if (active == null) return false
        active = null
        lastStopReason = reason
        return true
    }

    @Synchronized
    fun reconcileAllowlist(persistedAllowlist: Set<String>): Boolean {
        val session = current()
        val mustStop =
            session != null && !persistedAllowlist.containsAll(session.scope.allowedPackages)
        return mustStop && stop(AutomationStopReason.ALLOWLIST_CHANGED)
    }

    private fun expireIfNeeded(now: Instant) {
        val session = active ?: return
        when {
            now.isBefore(session.startedAt) -> stop(AutomationStopReason.CLOCK_ROLLBACK)
            !now.isBefore(session.scope.expiresAt) -> stop(AutomationStopReason.EXPIRED)
        }
    }

    private fun result(status: AutomationSessionStartStatus) = AutomationSessionStartResult(status)

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
        val MAX_TTL: Duration = DEFAULT_TTL
        const val DEFAULT_MAX_ACTIONS: Int = 30
    }
}
