package com.helix.extensions.mobileuse.automation

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
    val allowSystemSettings: Boolean = false,
    val conversationId: String? = null,
    val turnId: String? = null,
)

internal enum class AutomationActionAdmission {
    ADMITTED,
    NO_ACTIVE_SESSION,
    SESSION_PAUSED,
}

internal enum class AutomationActionCompletion {
    CONTINUE,
    BUDGET_EXHAUSTED,
    NO_ACTIVE_SESSION,
}

data class AutomationSessionStartResult(
    val status: AutomationSessionStartStatus,
    val session: ActiveAutomationSession? = null,
)

/**
 * Process-local user grant. No default time/action quota; optional user budgets remain effective.
 * Whole-phone access is granted only by the user-facing start entry, never by model arguments.
 * Clock rollback, expiry, budget exhaustion, allowlist reduction, screen lock,
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

    var resumeTarget: String? = null
        private set

    @Synchronized
    fun requestResumeOnTarget(packageName: String): Boolean {
        val session = current()
        if (session == null || pauseReason == null || !session.scope.permitsPackage(packageName)) return false
        resumeTarget = packageName
        return true
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Refusals stay before grant construction or mutation.
    @Synchronized
    fun start(
        requestedPackages: Set<String>,
        persistedAllowlist: Set<String>,
        ttl: Duration = DEFAULT_TTL,
        maxActions: Int = DEFAULT_MAX_ACTIONS,
        allowSystemSettings: Boolean = false,
        allApplications: Boolean = false,
    ): AutomationSessionStartResult {
        expireIfNeeded(clock.now())
        val refusal =
            when {
                active != null -> {
                    AutomationSessionStartStatus.SESSION_ALREADY_ACTIVE
                }

                !allApplications && requestedPackages.isEmpty() -> {
                    AutomationSessionStartStatus.EMPTY_TARGETS
                }

                !requestedPackages.all(AndroidPackageName::isValid) -> {
                    AutomationSessionStartStatus.INVALID_PACKAGE
                }

                !allApplications && !persistedAllowlist.containsAll(requestedPackages) -> {
                    AutomationSessionStartStatus.TARGET_NOT_ALLOWLISTED
                }

                ttl.isNegative -> {
                    AutomationSessionStartStatus.INVALID_TTL
                }

                maxActions < 0 -> {
                    AutomationSessionStartStatus.INVALID_ACTION_BUDGET
                }

                else -> {
                    null
                }
            }
        if (refusal != null) return result(refusal)

        val now = clock.now()
        val expires =
            try {
                if (ttl.isZero) Instant.MAX else now.plus(ttl)
            } catch (_: java.time.DateTimeException) {
                return result(AutomationSessionStartStatus.INVALID_TTL)
            } catch (_: ArithmeticException) {
                return result(AutomationSessionStartStatus.INVALID_TTL)
            }
        val grantId = idFactory()
        val session =
            ActiveAutomationSession(
                id = grantId,
                allowSystemSettings = allowSystemSettings || allApplications,
                startedAt = now,
                scope =
                    AutomationSessionScope(
                        allowedPackages = if (allApplications) emptySet() else requestedPackages.toSet(),
                        deniedPackages = emptySet(),
                        maxActions = maxActions,
                        expiresAt = expires,
                        allApplications = allApplications,
                        grantId = grantId,
                    ),
            )
        active = session
        lastStopReason = null
        pauseReason = null
        resumeTarget = null
        return AutomationSessionStartResult(AutomationSessionStartStatus.STARTED, session)
    }

    /** Rebuild physical state from durable user configuration; never restore an old runtime identity. */
    @Synchronized
    fun activate(
        grant: com.helix.extensions.mobileuse.config.MobileUseGrant,
        turnId: String? = null,
    ): ActiveAutomationSession {
        val current = current()
        if (current?.conversationId == grant.conversationId && current.scope == grant.scope &&
            current.turnId == turnId
        ) {
            return current
        }
        val restored =
            ActiveAutomationSession(
                idFactory(),
                clock.now(),
                grant.scope,
                allowSystemSettings =
                    grant.scope.allApplications ||
                        grant.scope.allowedPackages.any { it in SystemSettingsTargets.packages },
                conversationId = grant.conversationId,
                turnId = turnId,
            )
        active = restored
        pauseReason = null
        resumeTarget = null
        lastStopReason = null
        return restored
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
        resumeTarget = null
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

    /** A fresh, policy-checked snapshot can restore the existing grant, never extend it. */
    @Synchronized
    fun resumeOnVerifiedTarget(packageName: String): Boolean {
        val session = current() ?: return false
        val permitted =
            pauseReason == AutomationPauseReason.TARGET_CHANGED && session.scope.permitsPackage(packageName)
        if (permitted) {
            pauseReason = null
            resumeTarget = null
        }
        return permitted
    }

    @Synchronized
    fun resumeAfterUserConfirmation(): Boolean {
        if (current() == null || pauseReason == null) return false
        pauseReason = null
        resumeTarget = null
        return true
    }

    @Synchronized
    @Suppress("ReturnCount")
    internal fun admitAction(allowPaused: Boolean = false): AutomationActionAdmission {
        val session = current() ?: return AutomationActionAdmission.NO_ACTIVE_SESSION
        if (pauseReason != null && !allowPaused) return AutomationActionAdmission.SESSION_PAUSED
        check(session.scope.maxActions == 0 || session.attemptedActions < session.scope.maxActions) {
            "active automation session exceeded its action budget"
        }
        active =
            session.copy(
                attemptedActions = (session.attemptedActions.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        return AutomationActionAdmission.ADMITTED
    }

    @Synchronized
    internal fun completeAction(): AutomationActionCompletion {
        val session = current() ?: return AutomationActionCompletion.NO_ACTIVE_SESSION
        return when {
            session.scope.maxActions > 0 && session.attemptedActions >= session.scope.maxActions -> {
                check(stop(AutomationStopReason.ACTION_BUDGET_EXHAUSTED))
                AutomationActionCompletion.BUDGET_EXHAUSTED
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
            session != null && !session.scope.allApplications &&
                !persistedAllowlist.containsAll(session.scope.allowedPackages)
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
        val DEFAULT_TTL: Duration = Duration.ZERO
        const val DEFAULT_MAX_ACTIONS: Int = 0
    }
}

internal fun ActiveAutomationSession.matchesStop(
    conversation: String?,
    scopeRef: String?,
    runtimeId: String?,
): Boolean = id == runtimeId && conversationId == conversation && scope.toScopeRef() == scopeRef
