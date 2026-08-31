package com.helix.app.profile

import com.helix.app.internal.LineStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime safety profile (ADR-0005, accepted): [STANDARD] is the default of every
 * installation; [ADVANCED] exists only in the developer variant and must be
 * switched on explicitly after the user reads the risk explanation. The profile
 * is orthogonal to the compile-time consumer/developer boundary and is never a
 * ToolCall parameter (ADR-0005: model, MCP, Skill or imported content cannot
 * switch it).
 */
enum class SafetyProfile { STANDARD, ADVANCED }

/**
 * Persisted safety-profile state. Switching is a PURE state transition: the only
 * side effect is writing the profile value to [LineStore]. No system permission
 * is requested, no Runtime is installed, no Root session is opened and no network
 * request is made (ADR-0005/ADR-0006; NFR-011: 0 permission/Root/Runtime/network
 * side effects) — the developer variant in M2 gains no enabled capability from
 * the switch yet; each capability is enabled individually by its own later
 * milestone.
 */
interface SafetyProfileStore {
    val profile: SafetyProfile

    val flow: StateFlow<SafetyProfile>

    /**
     * Persists [profile]. Switching TO [SafetyProfile.ADVANCED] is refused
     * (IllegalArgumentException) when this build does not contain the Advanced
     * entry (consumer variant) — fail-closed, never a hidden switch
     * (ADR-0005: consumer must not enable developer-only modules).
     */
    fun switchTo(profile: SafetyProfile)
}

/**
 * [SafetyProfileStore] backed by a [LineStore] (SharedPreferences on device).
 * [advancedAvailable] is wired at compile time from the flavor-specific
 * [AdvancedProfileAvailability] — the consumer build passes `false`, so the
 * refusal path is unreachably honest there.
 */
class PersistedSafetyProfileStore(
    private val store: LineStore,
    private val advancedAvailable: Boolean,
) : SafetyProfileStore {
    private val _flow = MutableStateFlow(readStored())

    override val flow: StateFlow<SafetyProfile> = _flow.asStateFlow()

    override val profile: SafetyProfile
        get() = _flow.value

    override fun switchTo(profile: SafetyProfile) {
        require(profile != SafetyProfile.ADVANCED || advancedAvailable) {
            "ADVANCED is not available in this build (consumer builds run STANDARD only — ADR-0005)"
        }
        store.setLines(KEY, listOf(profile.name))
        _flow.value = profile
    }

    private fun readStored(): SafetyProfile =
        when (store.lines(KEY).firstOrNull()) {
            SafetyProfile.STANDARD.name -> {
                SafetyProfile.STANDARD
            }

            SafetyProfile.ADVANCED.name -> {
                when {
                    advancedAvailable -> SafetyProfile.ADVANCED

                    // A stored ADVANCED in a build without the entry is not honored:
                    // the profile can only widen where the code exists (ADR-0005).
                    else -> SafetyProfile.STANDARD
                }
            }

            else -> {
                SafetyProfile.STANDARD
            } // fresh install / reset -> STANDARD (ADR-0006)
        }

    private companion object {
        const val KEY = "safety_profile"
    }
}
