package com.helix.app.profile

import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafetyProfileStoreTest {
    @Test fun channelIdentityIsFixedAndConsistentAcrossRecreation() {
        SafetyProfile.entries.forEach { channel ->
            repeat(2) {
                val store = ChannelSafetyProfileStore(channel)
                assertEquals(channel, store.profile)
                assertEquals(channel, store.flow.value)
                assertFalse(store.flow is MutableStateFlow<*>)
            }
        }
    }

    @Test fun productionChannelSelectsItsProfileWithoutPreferencesOrExternalEffects() {
        val store = ChannelSafetyProfileStore(ChannelCapabilities.PROFILE)
        assertEquals(
            if (ChannelCapabilities.ADVANCED_AVAILABLE) SafetyProfile.ADVANCED else SafetyProfile.STANDARD,
            store.profile,
        )
        val parameters =
            ChannelSafetyProfileStore::class.java.constructors
                .single()
                .parameterTypes
        assertEquals(listOf(SafetyProfile::class.java), parameters.toList())
        assertFalse(SafetyProfileStore::class.java.methods.any { it.name == "switchTo" })
    }
}
