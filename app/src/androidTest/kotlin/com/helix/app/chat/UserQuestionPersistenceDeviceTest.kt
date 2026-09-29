package com.helix.app.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class UserQuestionPersistenceDeviceTest {
    @Test fun questionAndDismissalSurviveStorageReopen() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "question-${UUID.randomUUID()}"
            val directory = File(context.cacheDir, name)
            var storage = HelixStorage.open(context, "$name.db", directory)
            try {
                storage.sessions.create("session", "Questions", null, null, 1)
                val service = UserQuestionService(storage)
                val args = Json.parseToJsonElement("""{"question":"Format?","options":["PDF","Markdown"]}""").jsonObject
                service.offer("q", "session", null, args)
                service.offer("q", "session", null, args)
                assertEquals(1, service.pending("session").size)
                storage.close()
                storage = HelixStorage.open(context, "$name.db", directory)
                val restored = UserQuestionService(storage)
                assertEquals(listOf("PDF", "Markdown"), restored.pending("session").single().options)
                restored.dismiss(restored.pending("session").single())
                storage.close()
                storage = HelixStorage.open(context, "$name.db", directory)
                assertTrue(UserQuestionService(storage).pending("session").isEmpty())
            } finally {
                storage.close()
                context.deleteDatabase("$name.db")
                directory.deleteRecursively()
            }
        }
}
