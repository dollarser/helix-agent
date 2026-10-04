package com.helix.core.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MobileUseGrantStoreTest {
    private val records = mutableMapOf<String, List<String>>()
    private var sequence = 0

    private fun store() =
        MobileUseGrantStore(
            { records[it].orEmpty() },
            { key, value -> records[key] = value },
            { "grant-${++sequence}" },
        )

    @Test fun globalConfigurationUpdatesOnlySelectedConversationsAndInvalidatesProofs() {
        val store = store()
        store.configureGlobal(emptySet(), true)
        assertNull(store.find("a"))
        store.enableFromGlobal("a")
        store.enableFromGlobal("b")
        val first = store.find("a")!!
        assertNotEquals(first.scope.grantId, store.find("b")!!.scope.grantId)
        store.configureGlobal(setOf("com.allowed"), false)
        assertFalse(store.matches("a", first.scope.toScopeRef()))
        assertEquals(setOf("com.allowed"), store.find("a")!!.scope.allowedPackages)
        assertNull(store.find("c"))
        val narrowed = store.find("a")!!
        store.revoke("a")
        store.enableFromGlobal("a")
        assertFalse(store.matches("a", narrowed.scope.toScopeRef()))
        assertEquals(store.find("a"), store.authorize("a", setOf("com.allowed"), false))
    }

    @Test fun failedGlobalEditFailsClosedForEveryConversation() {
        var fail = false
        val store =
            MobileUseGrantStore({ records[it].orEmpty() }, { key, value ->
                check(!fail)
                records[key] = value
            })
        store.configureGlobal(emptySet(), true)
        store.enableFromGlobal("a")
        fail = true
        assertThrows(IllegalStateException::class.java) { store.configureGlobal(setOf("com.allowed"), false) }
        assertNull(store.find("a"))
        fail = false
        store.configureGlobal(setOf("com.allowed"), false)
        assertEquals(setOf("com.allowed"), store.find("a")!!.scope.allowedPackages)
    }

    @Test fun recreationRestoresTheSameExplicitConversationConfiguration() {
        val grant = store().authorize("conversation-a", setOf("com.example.App"), false)
        repeat(3) { assertEquals(grant, store().find("conversation-a")) }
        assertEquals(Instant.MAX, grant.scope.expiresAt)
        assertEquals(0, grant.scope.maxActions)
        assertTrue(grant.shareScreens)
        assertNull(store().find("conversation-b"))
        assertFalse(store().matches("conversation-b", grant.scope.toScopeRef()))
        assertFalse(store().matches(null, grant.scope.toScopeRef()))
    }

    @Test fun identicalUserSaveDoesNotAskAgainButScopeChangesInvalidateOldProof() {
        val store = store()
        val first = store.authorize("a", setOf("com.app.one"), false)
        assertEquals(first, store.authorize("a", setOf("com.app.one"), false))
        val expanded = store.authorize("a", emptySet(), true)
        assertNotEquals(first.scope.grantId, expanded.scope.grantId)
        assertFalse(store.matches("a", first.scope.toScopeRef()))
        assertTrue(store.matches("a", expanded.scope.toScopeRef()))
        val narrowed = store.authorize("a", setOf("com.app.two"), false)
        assertFalse(narrowed.scope.permitsPackage("com.app.one"))
        assertFalse(store.matches("a", expanded.scope.toScopeRef()))
    }

    @Test fun revocationIsDurableAndCannotAffectAnotherConversation() {
        val a = store().authorize("a", emptySet(), true)
        val b = store().authorize("b", setOf("com.app.two"), false)
        store().revoke("a")
        assertNull(store().find("a"))
        assertEquals(b, store().find("b"))
        val restarted = store().authorize("a", emptySet(), true)
        assertNotEquals(a.scope.grantId, restarted.scope.grantId)
    }

    @Test fun fullAppSelectionSurvivesPersistenceWithoutScopeRefTruncation() {
        val packages = (1..1000).map { "com.example.application$it" }.toSet()
        val first = store().authorize("large", packages, false)
        assertEquals(packages, store().find("large")!!.scope.allowedPackages)
        assertTrue(first.scope.toScopeRef().length <= UserScope.MAX_SCOPE_REF_LENGTH)
        assertFalse(first.scope.permitsPackage("com.unselected.app"))
    }

    @Test fun malformedOrCrossConversationRecordsNeverProduceAuthorization() {
        store().authorize("a", emptySet(), true)
        val key = records.keys.single()
        records[key] = records.getValue(key).toMutableList().also { it[1] = "b" }
        assertThrows(IllegalStateException::class.java) { store().find("a") }
        assertNull(store().find("b"))
    }

    @Test fun failedRevocationDoesNotLeaveAuthorizationUsableInTheCurrentProcess() {
        var fail = false
        val store =
            MobileUseGrantStore(
                { records[it].orEmpty() },
                { key, value ->
                    check(!fail)
                    records[key] = value
                },
            )
        val grant = store.authorize("a", emptySet(), true)
        fail = true
        assertThrows(IllegalStateException::class.java) { store.revoke("a") }
        assertNull(store.find("a"))
        assertFalse(store.matches("a", grant.scope.toScopeRef()))
        fail = false
        store.revoke("a")
        assertNull(store().find("a"))
    }

    @Test fun unsuccessfulPersistenceIsNotReportedAsSaved() {
        val store = MobileUseGrantStore({ emptyList() }, { _, _ -> error("Storage unavailable") })
        assertThrows(IllegalStateException::class.java) { store.authorize("a", emptySet(), true) }
    }
}
