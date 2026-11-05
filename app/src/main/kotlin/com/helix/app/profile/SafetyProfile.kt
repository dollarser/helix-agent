package com.helix.app.profile

import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only channel capability identity; never grants session scope or Android permissions. */
interface SafetyProfileStore {
    val profile: SafetyProfile
    val flow: StateFlow<SafetyProfile>
}

/** Fixed by the build channel. Preferences and imported configuration cannot change it. */
class ChannelSafetyProfileStore(
    override val profile: SafetyProfile,
) : SafetyProfileStore {
    override val flow: StateFlow<SafetyProfile> = MutableStateFlow(profile).asStateFlow()
}
