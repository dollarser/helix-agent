package com.helix.app

import com.helix.app.internal.InMemoryLineStore
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupOnboardingTest {
    @Test fun acknowledgementAndPermissionReviewSurviveStoreRecreation() {
        val backing = InMemoryLineStore()
        val store = FirstLaunchStore(backing)
        assertEquals(StartupOnboarding.NOTICE, store.onboarding)
        store.acceptNotice()
        assertEquals(StartupOnboarding.PERMISSIONS, FirstLaunchStore(backing).onboarding)
        FirstLaunchStore(backing).markSeen()
        assertEquals(StartupOnboarding.COMPLETE, FirstLaunchStore(backing).onboarding)
        store.reset()
        assertEquals(StartupOnboarding.NOTICE, store.onboarding)
    }

    @Test fun previouslyAcknowledgedUsersDoNotGetUpgradeOnboarding() {
        val backing = InMemoryLineStore()
        backing.setLines("first_launch", listOf("1"))
        assertEquals(StartupOnboarding.COMPLETE, FirstLaunchStore(backing).onboarding)
    }
}
