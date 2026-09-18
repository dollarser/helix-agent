package com.helix.app.readiness

import com.helix.core.model.Capability
import com.helix.core.policy.CapabilityGrant

/**
 * HXA-205 readiness goals — the user-facing objectives the readiness view reasons about. The goal
 * is the lens the projection aggregates through: each goal declares the model / workspace /
 * system-capability / runtime prerequisites that matter for IT and reports the next action for
 * that goal. The goals are DISTINCT operations, never one generic "resume" (the cross-execution-
 * domain recovery invariant, HXA-204): configuring a model, verifying the Runtime and repairing
 * the Runtime are different facts with different next actions.
 */
enum class ReadinessGoal {
    /** Talk to a model — needs a configured provider. */
    CHAT,

    /** Manage files — the app's private workspace scope is always available. */
    FILES,

    /** Browse the web — needs the platform WebView present. */
    BROWSER,

    /** Run Linux in the PRoot Runtime — developer flavor only. */
    LINUX,
}

/**
 * Flavor-neutral runtime readiness. The developer flavor resolves the LIVE PRoot gate (installed +
 * verified + enabled); the consumer flavor is always [NOT_AVAILABLE] because that build ships no
 * PRoot capability (ADR-0005/0013) — the readiness view must never offer a Runtime item it cannot
 * fulfil, so consumer has no LINUX goal to begin with.
 */
enum class RuntimeReadiness {
    READY,
    NOT_INSTALLED,
    NOT_VERIFIED,
    DISABLED_OR_FORCED_STOPPED,
    NOT_AVAILABLE,
}

/** The state of one readiness item. */
enum class ReadinessState {
    /** The prerequisite is satisfied right now. */
    READY,

    /** The prerequisite is absent but the user can fix it with a concrete in-app action. */
    MISSING,

    /** This build / device offers the prerequisite at all — honestly unavailable, no in-app action. */
    NOT_AVAILABLE,
}

/** The kind of one readiness item — a structured fact the UI maps to its label (HXA-204 pattern). */
enum class ReadinessItemKind {
    MODEL,
    WORKSPACE,
    WEB,
    RUNTIME,
}

/** The distinct next actions the readiness view can offer — never collapsed into one "retry". */
enum class ReadinessActionKind {
    /** Open the provider configuration (the model connection is missing). */
    ADD_MODEL,

    /** The developer-only zero-Job cold-bind verification ("验证 Runtime"). */
    VERIFY_RUNTIME,

    /** The developer-only repair-activity entry ("修复 Runtime"); also the install path — no
     * separate APK install flow is shown (HXA-205). */
    REPAIR_RUNTIME,
}

/** One aggregated readiness item — a single prerequisite of the goal. */
data class ReadinessItem(
    val kind: ReadinessItemKind,
    val state: ReadinessState,
    val action: ReadinessActionKind?,
)

/**
 * The read-only projection for one goal. [ready] is true when every prerequisite is satisfied;
 * [nextAction] is the single most-important next action (the first non-ready item's action).
 */
data class ReadinessProjection(
    val goal: ReadinessGoal,
    val ready: Boolean,
    val items: List<ReadinessItem>,
) {
    val nextAction: ReadinessActionKind?
        get() = items.firstOrNull { it.state != ReadinessState.READY }?.action
}

/**
 * HXA-205 read-only readiness projection. Pure: it maps ALREADY-READ facts (model configured, a
 * read-only capability resolver, the runtime readiness) to per-goal missing items + the next
 * action. It starts no service, logs in, or installs — the caller reads the facts with the
 * existing read-only services and hands them in. Passive entry of the readiness view therefore
 * never cold-binds the Runtime or opens a login (HXA-205: 被动进入页面不拉起Runtime或登录); only an
 * explicit user click on a next action performs a cold bind / repair.
 */
internal object CapabilityReadiness {
    fun project(
        goal: ReadinessGoal,
        modelConfigured: Boolean,
        capability: (Capability) -> CapabilityGrant,
        runtime: RuntimeReadiness,
    ): ReadinessProjection =
        when (goal) {
            ReadinessGoal.CHAT -> chat(modelConfigured)
            ReadinessGoal.FILES -> files()
            ReadinessGoal.BROWSER -> browser(capability)
            ReadinessGoal.LINUX -> linux(runtime)
        }

    // The model connection is the only prerequisite for chatting; a missing provider maps to the
    // concrete "configure a model" action, never a generic retry.
    private fun chat(modelConfigured: Boolean): ReadinessProjection {
        val item =
            ReadinessItem(
                kind = ReadinessItemKind.MODEL,
                state = if (modelConfigured) ReadinessState.READY else ReadinessState.MISSING,
                action = if (modelConfigured) null else ReadinessActionKind.ADD_MODEL,
            )
        return ReadinessProjection(ReadinessGoal.CHAT, modelConfigured, listOf(item))
    }

    // The app's private workspace scope is created at container init and is always available, so
    // file management never waits for model configuration (HXA-205: 无模型配置时文件管理/浏览器仍可用).
    private fun files(): ReadinessProjection {
        val item = ReadinessItem(ReadinessItemKind.WORKSPACE, ReadinessState.READY, null)
        return ReadinessProjection(ReadinessGoal.FILES, true, listOf(item))
    }

    // WEB_BROWSING is a device presence (WebView package), not a permission: granted → ready,
    // otherwise honestly unavailable with no in-app fix.
    private fun browser(capability: (Capability) -> CapabilityGrant): ReadinessProjection {
        val grant = capability(Capability.WEB_BROWSING)
        val state =
            when {
                grant.isUsable -> {
                    ReadinessState.READY
                }

                grant.state == com.helix.core.policy.GrantState.UNAVAILABLE -> {
                    ReadinessState.NOT_AVAILABLE
                }

                else -> {
                    ReadinessState.MISSING
                }
            }
        val item = ReadinessItem(ReadinessItemKind.WEB, state, null)
        return ReadinessProjection(ReadinessGoal.BROWSER, state == ReadinessState.READY, listOf(item))
    }

    // The PRoot Runtime is developer-only. NOT_VERIFIED maps to the zero-Job verify; the install /
    // disabled states map to the SAME existing repair-activity entry (no separate APK install flow).
    private fun linux(runtime: RuntimeReadiness): ReadinessProjection {
        val (state, action) =
            when (runtime) {
                RuntimeReadiness.READY -> {
                    ReadinessState.READY to null
                }

                RuntimeReadiness.NOT_VERIFIED -> {
                    ReadinessState.MISSING to ReadinessActionKind.VERIFY_RUNTIME
                }

                RuntimeReadiness.NOT_INSTALLED,
                RuntimeReadiness.DISABLED_OR_FORCED_STOPPED,
                -> {
                    ReadinessState.MISSING to ReadinessActionKind.REPAIR_RUNTIME
                }

                RuntimeReadiness.NOT_AVAILABLE -> {
                    ReadinessState.NOT_AVAILABLE to null
                }
            }
        val item = ReadinessItem(ReadinessItemKind.RUNTIME, state, action)
        return ReadinessProjection(
            ReadinessGoal.LINUX,
            runtime == RuntimeReadiness.READY,
            listOf(item),
        )
    }
}
