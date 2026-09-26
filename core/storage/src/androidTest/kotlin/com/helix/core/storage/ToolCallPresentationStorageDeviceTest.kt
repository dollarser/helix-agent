package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class ToolCallPresentationStorageDeviceTest {
    @Test
    fun modelIntentSurvivesStorageReopenWithBusinessArgsUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "tool-presentation-${UUID.randomUUID()}.db"
        val root = File(context.cacheDir, name)
        val states = listOf("COMPLETED", "AWAITING_APPROVAL", "DENIED", "FAILED", "NEEDS_REVIEW")
        try {
            val storage = HelixStorage.open(context, name, root)
            try {
                storage.sessions.create("session", "Tool intent", null, null, 1)
                storage.turns.start("turn", "session", 2)
                states.forEach { state ->
                    storage.toolCalls.append(
                        id = state,
                        turnId = "turn",
                        callId = state,
                        name = "time.now",
                        version = "1",
                        argsJson = "{}",
                        state = state,
                        modelIntent = "Inspect $state",
                    )
                }
            } finally {
                storage.close()
            }

            val reopened = HelixStorage.open(context, name, root)
            try {
                val calls = states.map(reopened.toolCalls::resolve)
                calls.zip(states).forEach { (call, state) ->
                    assertEquals("{}", call.argsJson)
                    assertEquals("Inspect $state", call.modelIntent)
                    assertEquals(state, call.state)
                }
                // Different presentation/state cannot change the business-argument identity.
                assertEquals(1, calls.map { it.argsHash }.distinct().size)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }
}
