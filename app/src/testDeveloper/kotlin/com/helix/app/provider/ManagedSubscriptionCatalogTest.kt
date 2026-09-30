package com.helix.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ManagedSubscriptionCatalogTest {
    @Test fun subscriptionOrderAndLabelsMatchTheProductDecision() {
        assertEquals(
            listOf("Codex", "Claude", "Google Antigravity", "GitHub Copilot", "Grok (X Premium)"),
            ManagedSubscriptionCatalog.entries.map { it.label },
        )
        assertEquals(ManagedSubscriptionCatalog.entries.map { it.id }, SubscriptionProviderModule.providerIds)
        assertEquals(5, SubscriptionProviderModule.providerIds.distinct().size)
        assertFalse(SubscriptionProviderModule.isManaged("kimi-code"))
        assertFalse(SubscriptionProviderModule.isManaged("minimax-token-cn"))
    }
}
