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
    ACTION_BUDGET_EXHAUSTED,
    DEVICE_LOCKED,
}

enum class AutomationPauseReason {
    TARGET_CHANGED,
    CHECKPOINT,
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
    INVALID_ACTION_BUDGET,
}

data class ActiveAutomationSession(
    val id: String,
    val startedAt: Instant,
    val scope: AutomationSessionScope,
    val attemptedActions: Int = 0,
)

internal enum class AutomationActionAdmission {
    ADMITTED,
    NO_ACTIVE_SESSION,
    SESSION_PAUSED,
}

internal enum class AutomationActionCompletion {
    CONTINUE,
    CHECKPOINT_REQUIRED,
    BUDGET_EXHAUSTED,
    NO_ACTIVE_SESSION,
}

data class AutomationSessionStartResult(
    val status: AutomationSessionStartStatus,
    val session: ActiveAutomationSession? = null,
)

/**
 * Process-local, single-session lifecycle. Five minutes and 30 actions remain both the defaults
 * and release hard maxima; callers may only choose a smaller positive budget. Every ten attempts
 * requires a fresh user confirmation, including failed or refused attempts, and confirmations
 * cannot be banked. Clock rollback, expiry, budget exhaustion, allowlist reduction, screen lock,
 * or service loss closes the session rather than preserving a stale grant.
 */
@Suppress("TooManyFunctions")
class AutomationSessionManager(
    private val clock: Clock,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private var active: ActiveAutomationSession? = null

    @Volatile
    var lastStopReason: AutomationStopReason? = null
        private set

    @Volatile
    var pauseReason: AutomationPauseReason? = null
        private set

    @Synchronized
    fun start(
        requestedPackages: Set<String>,
        persistedAllowlist: Set<String>,
        ttl: Duration = DEFAULT_TTL,
        maxActions: Int = DEFAULT_MAX_ACTIONS,
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

                maxActions !in 1..MAX_ACTIONS -> {
                    AutomationSessionStartStatus.INVALID_ACTION_BUDGET
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
                        maxActions = maxActions,
                        expiresAt = now.plus(ttl),
                    ),
            )
        active = session
        lastStopReason = null
        pauseReason = null
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
        pauseReason = null
        return true
    }

    @Synchronized
    fun pause(reason: AutomationPauseReason): Boolean {
        if (current() == null) return false
        pauseReason = reason
        return true
    }

    @Synchronized
    fun isPaused(): Boolean = current() != null && pauseReason != null

    @Synchronized
    fun resumeAfterUserConfirmation(): Boolean {
        if (current() == null || pauseReason == null) return false
        pauseReason = null
        return true
    }

    @Synchronized
    @Suppress("ReturnCount")
    internal fun admitAction(): AutomationActionAdmission {
        val session = current() ?: return AutomationActionAdmission.NO_ACTIVE_SESSION
        if (pauseReason != null) return AutomationActionAdmission.SESSION_PAUSED
        check(session.attemptedActions < session.scope.maxActions) {
            "active automation session exceeded its action budget"
        }
        active = session.copy(attemptedActions = session.attemptedActions + 1)
        return AutomationActionAdmission.ADMITTED
    }

    @Synchronized
    internal fun completeAction(): AutomationActionCompletion {
        val session = current() ?: return AutomationActionCompletion.NO_ACTIVE_SESSION
        return when {
            session.attemptedActions >= session.scope.maxActions -> {
                check(stop(AutomationStopReason.ACTION_BUDGET_EXHAUSTED))
                AutomationActionCompletion.BUDGET_EXHAUSTED
            }

            session.attemptedActions % CHECKPOINT_INTERVAL == 0 -> {
                check(pause(AutomationPauseReason.CHECKPOINT))
                AutomationActionCompletion.CHECKPOINT_REQUIRED
            }

            else -> {
                AutomationActionCompletion.CONTINUE
            }
        }
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
        const val MAX_ACTIONS: Int = DEFAULT_MAX_ACTIONS
        const val CHECKPOINT_INTERVAL: Int = 10
    }
}
