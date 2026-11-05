package com.helix.app.profile

import com.helix.core.model.SafetyProfile

/** Compile-time capability identity; authorization is checked separately at execution. */
internal object ChannelCapabilities {
    val PROFILE: SafetyProfile = SafetyProfile.STANDARD
    const val ADVANCED_AVAILABLE: Boolean = false
}
