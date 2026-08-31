package com.helix.app.profile

import com.helix.app.internal.InMemoryLineStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafetyProfileStoreTest {
    @Test
    fun freshInstallStartsStandard() {
        val store = PersistedSafetyProfileStore(InMemoryLineStore(), advancedAvailable = true)
        assertEquals(SafetyProfile.STANDARD, store.profile)
    }

    @Test
    fun developerSwitchToAdvancedPersistsAndEmits() {
        val backing = InMemoryLineStore()
        val store = PersistedSafetyProfileStore(backing, advancedAvailable = true)
        store.switchTo(SafetyProfile.ADVANCED)
        assertEquals(SafetyProfile.ADVANCED, store.profile)
        // A second store instance on the same backing sees the persisted value
        // (survives process restart — ADR-0006: entering/leaving Advanced keeps
        // the installation state).
        val reloaded = PersistedSafetyProfileStore(backing, advancedAvailable = true)
        assertEquals(SafetyProfile.ADVANCED, reloaded.profile)
    }

    @Test
    fun switchBackToStandardPersists() {
        val store = PersistedSafetyProfileStore(InMemoryLineStore(), advancedAvailable = true)
        store.switchTo(SafetyProfile.ADVANCED)
        store.switchTo(SafetyProfile.STANDARD)
        assertEquals(SafetyProfile.STANDARD, store.profile)
    }

    @Test
    fun consumerBuildRefusesAdvancedSwitch() {
        val store = PersistedSafetyProfileStore(InMemoryLineStore(), advancedAvailable = false)
        assertThrows(IllegalArgumentException::class.java) { store.switchTo(SafetyProfile.ADVANCED) }
        assertEquals(SafetyProfile.STANDARD, store.profile)
    }

    @Test
    fun storedAdvancedIsNotHonoredInABuildWithoutTheEntry() {
        val backing = InMemoryLineStore()
        PersistedSafetyProfileStore(backing, advancedAvailable = true).switchTo(SafetyProfile.ADVANCED)
        // Simulate the same preferences loaded by a build without the entry:
        // the profile can only widen where the code exists (ADR-0005).
        val consumer = PersistedSafetyProfileStore(backing, advancedAvailable = false)
        assertEquals(SafetyProfile.STANDARD, consumer.profile)
    }

    @Test
    fun unknownStoredValueDegradesToStandard() {
        val backing = InMemoryLineStore()
        backing.setLines("safety_profile", listOf("SOMETHING_ELSE"))
        assertEquals(SafetyProfile.STANDARD, PersistedSafetyProfileStore(backing, advancedAvailable = true).profile)
    }

    @Test
    fun switchingHasNoCollaboratorOtherThanTheStore() {
        // Structural guarantee of the NFR-011 zero-side-effect switch: the store's
        // only dependencies are the LineStore (persistence) and the compile-time
        // availability flag — there is no network/permission/runtime collaborator
        // to invoke. The constructor surface is asserted so a future
        // collaborator cannot sneak in unnoticed.
        val params =
            PersistedSafetyProfileStore::class.java.constructors
                .first()
                .parameterTypes
        assertEquals(2, params.size)
        assertEquals(com.helix.app.internal.LineStore::class.java, params[0])
        assertEquals(Boolean::class.javaPrimitiveType, params[1])
    }
}
