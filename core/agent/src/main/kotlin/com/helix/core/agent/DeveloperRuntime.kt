package com.helix.core.agent

import com.helix.core.agent.DeveloperRuntimeState.Status
import com.helix.core.agent.DeveloperRuntimeState.Step

/**
 * The "Enable Developer Runtime" onboarding lifecycle (P1, research doc section 8 developer loop,
 * the "Shell / Linux Runtime" spec): check environment, download, verify, install, self-test, then
 * Ready — over the capability states Not installed, Installing, Ready, Broken, Repair, Update
 * available.
 *
 * A pure, fail-closed contract: it models the states and the legal transitions only, and performs
 * no download, install or I/O. [reduce] throws [IllegalStateException] on an illegal or out-of-order
 * event (an illegal transition is a bug, never a silent success). The carried
 * [DeveloperRuntimeState.detail] is bounded and human, never a raw log or a path. The app's real
 * provisioning actions drive it and the Capability Center row reads [DeveloperRuntimeState.label].
 */
object DeveloperRuntime {
    /** The capability's initial state: nothing is installed yet. */
    val NOT_INSTALLED: DeveloperRuntimeState = DeveloperRuntimeState(Status.NOT_INSTALLED)

    /** Applies [event] to [current] and returns the next state; throws on an illegal transition. */
    fun reduce(
        current: DeveloperRuntimeState,
        event: DeveloperRuntimeEvent,
    ): DeveloperRuntimeState =
        when (event) {
            is DeveloperRuntimeEvent.BeginInstall -> {
                beginInstall(current)
            }

            is DeveloperRuntimeEvent.BeginRepair -> {
                beginRepair(current)
            }

            is DeveloperRuntimeEvent.EnvironmentChecked -> {
                step(current, Step.CHECK_ENVIRONMENT, event.ok, event.detail, Step.DOWNLOAD)
            }

            is DeveloperRuntimeEvent.Downloaded -> {
                step(current, Step.DOWNLOAD, event.ok, event.detail, Step.VERIFY)
            }

            is DeveloperRuntimeEvent.Verified -> {
                step(current, Step.VERIFY, event.ok, event.detail, Step.INSTALL)
            }

            is DeveloperRuntimeEvent.Installed -> {
                step(current, Step.INSTALL, event.ok, event.detail, Step.SELF_TEST)
            }

            is DeveloperRuntimeEvent.SelfTested -> {
                selfTest(current, event.ok, event.detail)
            }

            is DeveloperRuntimeEvent.UpdateAvailable -> {
                updateAvailable(current)
            }

            is DeveloperRuntimeEvent.IntegrityFailed -> {
                integrityFailed(current, event.detail)
            }

            is DeveloperRuntimeEvent.Uninstall -> {
                DeveloperRuntimeState(Status.NOT_INSTALLED)
            }
        }

    private fun beginInstall(current: DeveloperRuntimeState): DeveloperRuntimeState {
        if (current.status != Status.NOT_INSTALLED && current.status != Status.UPDATE_AVAILABLE) {
            throw illegal(current, "BeginInstall")
        }
        return DeveloperRuntimeState(Status.INSTALLING, Step.CHECK_ENVIRONMENT)
    }

    private fun beginRepair(current: DeveloperRuntimeState): DeveloperRuntimeState {
        if (current.status != Status.BROKEN) {
            throw illegal(current, "BeginRepair")
        }
        return DeveloperRuntimeState(Status.REPAIRING, Step.CHECK_ENVIRONMENT)
    }

    private fun step(
        current: DeveloperRuntimeState,
        expected: Step,
        ok: Boolean,
        detail: String?,
        next: Step,
    ): DeveloperRuntimeState {
        val inProgress = requireInProgress(current, expected)
        return if (ok) inProgress.copy(step = next, detail = null) else broken(detail)
    }

    private fun selfTest(
        current: DeveloperRuntimeState,
        ok: Boolean,
        detail: String?,
    ): DeveloperRuntimeState {
        requireInProgress(current, Step.SELF_TEST)
        return if (ok) DeveloperRuntimeState(Status.READY) else broken(detail)
    }

    private fun updateAvailable(current: DeveloperRuntimeState): DeveloperRuntimeState {
        if (current.status != Status.READY) {
            throw illegal(current, "UpdateAvailable")
        }
        return DeveloperRuntimeState(Status.UPDATE_AVAILABLE)
    }

    private fun integrityFailed(
        current: DeveloperRuntimeState,
        detail: String?,
    ): DeveloperRuntimeState {
        if (current.status != Status.READY && current.status != Status.UPDATE_AVAILABLE) {
            throw illegal(current, "IntegrityFailed")
        }
        return broken(detail)
    }

    private fun requireInProgress(
        current: DeveloperRuntimeState,
        expected: Step,
    ): DeveloperRuntimeState {
        if (current.status != Status.INSTALLING && current.status != Status.REPAIRING) {
            throw illegal(current, "a step outcome")
        }
        check(current.step == expected) { "expected $expected but the runtime is on ${current.step}" }
        return current
    }

    private fun broken(detail: String?): DeveloperRuntimeState {
        val boundedDetail = detail?.let { if (it.length <= MAX_DETAIL) it else it.take(MAX_DETAIL) + "…" }
        return DeveloperRuntimeState(Status.BROKEN, null, boundedDetail)
    }

    private fun illegal(
        current: DeveloperRuntimeState,
        event: String,
    ): IllegalStateException =
        IllegalStateException("$event is not valid from status=${current.status}, step=${current.step}")

    private const val MAX_DETAIL = 512
}

/** A capability state (research doc section 8 "Shell / Linux Runtime"; section 11 Capability row). */
data class DeveloperRuntimeState(
    val status: Status,
    val step: Step? = null,
    val detail: String? = null,
) {
    /** True when the runtime can run work: Ready, or Ready with an update still to apply. */
    val isUsable: Boolean
        get() = status == Status.READY || status == Status.UPDATE_AVAILABLE

    /** "Installing: Verify" while onboarding, otherwise the plain status label. */
    val progressLabel: String
        get() = step?.let { "${status.label}: ${it.label}" } ?: status.label

    enum class Status {
        NOT_INSTALLED,
        INSTALLING,
        READY,
        BROKEN,
        REPAIRING,
        UPDATE_AVAILABLE,
        ;

        /** The Capability Center row label for this state (doc section 11). */
        val label: String
            get() =
                when (this) {
                    NOT_INSTALLED -> "Not installed"
                    INSTALLING -> "Installing"
                    READY -> "Ready"
                    BROKEN -> "Broken"
                    REPAIRING -> "Repair"
                    UPDATE_AVAILABLE -> "Update available"
                }
    }

    /** The onboarding sub-step, meaningful only while Installing or Repairing. */
    enum class Step {
        CHECK_ENVIRONMENT,
        DOWNLOAD,
        VERIFY,
        INSTALL,
        SELF_TEST,
        ;

        val label: String
            get() =
                when (this) {
                    CHECK_ENVIRONMENT -> "Check environment"
                    DOWNLOAD -> "Download"
                    VERIFY -> "Verify"
                    INSTALL -> "Install"
                    SELF_TEST -> "Self test"
                }
    }
}

/** An event that drives the [DeveloperRuntime] lifecycle (doc section 8 onboarding flow). */
sealed interface DeveloperRuntimeEvent {
    /** Start onboarding from Not installed (or re-run it to apply an available update). */
    object BeginInstall : DeveloperRuntimeEvent

    /** Re-run the onboarding flow to recover a Broken runtime. */
    object BeginRepair : DeveloperRuntimeEvent

    /** Environment check finished; success advances to download, failure lands in Broken. */
    data class EnvironmentChecked(
        val ok: Boolean,
        val detail: String? = null,
    ) : DeveloperRuntimeEvent

    data class Downloaded(
        val ok: Boolean,
        val detail: String? = null,
    ) : DeveloperRuntimeEvent

    data class Verified(
        val ok: Boolean,
        val detail: String? = null,
    ) : DeveloperRuntimeEvent

    data class Installed(
        val ok: Boolean,
        val detail: String? = null,
    ) : DeveloperRuntimeEvent

    /** The self-test finished; success is the only path to Ready. */
    data class SelfTested(
        val ok: Boolean,
        val detail: String? = null,
    ) : DeveloperRuntimeEvent

    /** A newer version exists while Ready. */
    object UpdateAvailable : DeveloperRuntimeEvent

    /** A runtime reconciliation or integrity check failed while usable. */
    data class IntegrityFailed(
        val detail: String? = null,
    ) : DeveloperRuntimeEvent

    /** Remove the runtime (also cancels an in-progress install or repair). */
    object Uninstall : DeveloperRuntimeEvent
}
