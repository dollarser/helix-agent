package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Chat Reply is currently packaged by the Developer composition root only. */
class ChatReplyContributionDeviceTest {
    @Test fun nativeGuideUsesTheSameHashPinnedRepositoryAndCannotBecomeStandalone() {
        val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
        val session = UUID.randomUUID().toString()
        c.storage.sessions.create(session, "Native guide fixture", null, null, 0)
        val plugin = "bundled-plugin:chat-reply"
        try {
            val key =
                c.skillRepository
                    .list()
                    .single { it.key.name == "chat-reply" }
                    .key
            assertFalse(c.pluginService.standaloneSkills().any { it.key == key })
            assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key, session) }
            c.pluginService.selectFromUser(session, plugin, true)
            assertFalse(c.pluginRegistry.hasPublishedTools("chat-reply"))
            assertTrue(c.pluginRegistry.ready("chat-reply"))
            assertTrue(
                c.skillRepository
                    .read(key, session)
                    .body
                    .contains("Chat reply assistant"),
            )
            c.pluginService.selectFromUser(session, plugin, false)
            assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key, session) }
            assertTrue(
                c.skillRepository
                    .inspect(key)
                    .body
                    .contains("Chat reply assistant"),
            )
        } finally {
            c.storage.deleteSessionPermanently(session)
        }
    }
}
