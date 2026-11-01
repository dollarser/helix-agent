package com.helix.app.chat

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.UUID

class ToolInspectionQueryDeviceTest {
    @Test fun originalSessionOnlyRedactsInstalledCredentialsWithoutExecutingOrMutating() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val name = "inspection-${UUID.randomUUID()}.db"
        val root = File(app.filesDir, name)
        val storage = HelixStorage.open(app, name, root)
        val alias = SecretAlias("inspection-fixture-${UUID.randomUUID()}")
        val secret = "synthetic-credential-123456789"
        try {
            storage.secrets.put(alias, secret)
            storage.sessions.create("s", "Synthetic inspector", null, null, 1)
            storage.turns.start("t", "s", 2)
            storage.toolCalls.append("row", "t", "call", "read", "1", "{\"text\":\"$secret\"}", "COMPLETED")
            storage.toolResults.append("result", "row", "SUCCEEDED", "bounded", "{\"body\":\"$secret\"}")
            val query = ToolInspectionQuery(storage)
            val read = requireNotNull(query.read("s", "t", "call"))
            assertFalse(read.arguments.contains(secret))
            assertFalse(requireNotNull(read.result).contains(secret))
            assertNull(query.read("other", "t", "call"))
            assertNull(query.read("s", "t", "missing"))
            storage.toolCalls.append("big", "t", "big", "read", "1", "{}", "COMPLETED")
            storage.toolResults.append("big-result", "big", "SUCCEEDED", "bounded", "x".repeat(1_048_577))
            assertThrows(IllegalArgumentException::class.java) { query.read("s", "t", "big") }
            assertEquals(2, storage.toolCalls.listByTurn("t").size)
            assertEquals("COMPLETED", storage.toolCalls.resolve("row").state)
        } finally {
            storage.secrets.delete(alias)
            storage.close()
            app.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
