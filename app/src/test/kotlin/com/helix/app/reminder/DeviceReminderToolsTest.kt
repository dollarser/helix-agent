package com.helix.app.reminder

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.storage.reminder.LocalReminder
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeviceReminderToolsTest {
    private val backend = Fake()
    private val access = DeviceReminderTools.Access(backend)

    private fun call(
        name: String,
        args: String = "{}",
        session: String = "a",
        cancelled: Boolean = false,
    ) = access.execute(
        name,
        ExecutableToolCall(
            "call",
            name,
            "1",
            Json.parseToJsonElement(args) as JsonObject,
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(30),
            object : CancelSignal {
                override fun isCancelled() = cancelled
            },
            sessionId = session,
            turnId = "turn",
        ),
    )

    @Test fun cancelRequiresList() {
        assertTrue(call(DeviceReminderTools.CANCEL, """{"id":"one","revision":1}""") is ToolExecutorResult.Failed)
        assertEquals(0, backend.cancellations)
    }

    @Test fun listedRevisionCanBeCancelled() {
        call(DeviceReminderTools.LIST)
        assertTrue(call(DeviceReminderTools.CANCEL, """{"id":"one","revision":1}""") is ToolExecutorResult.Completed)
        assertEquals(1, backend.cancellations)
        assertTrue(call(DeviceReminderTools.CANCEL, """{"id":"one","revision":1}""") is ToolExecutorResult.Failed)
    }

    @Test fun listCannotAuthorizeOtherSession() {
        call(DeviceReminderTools.LIST)
        assertTrue(
            call(
                DeviceReminderTools.CANCEL,
                """{"id":"one","revision":1}""",
                session = "b",
            ) is ToolExecutorResult.Failed,
        )
        assertEquals(0, backend.cancellations)
    }

    @Test fun changedRevisionRefusedByDurableBackend() {
        call(DeviceReminderTools.LIST)
        backend.item = backend.item.copy(revision = 2)
        assertTrue(call(DeviceReminderTools.CANCEL, """{"id":"one","revision":1}""") is ToolExecutorResult.Failed)
    }

    @Test fun cancelledCreateDoesNotSave() {
        assertEquals(
            ToolExecutorResult.Cancelled,
            call(
                DeviceReminderTools.CREATE,
                """{"time":"2030-01-01T09:00:00+08:00","label":"Test"}""",
                cancelled = true,
            ),
        )
        assertEquals(0, backend.saves)
    }

    @Test fun timeMustHaveOffsetAndLabel() {
        assertTrue(
            call(
                DeviceReminderTools.CREATE,
                """{"time":"2030-01-01T09:00:00","label":"Test"}""",
            ) is ToolExecutorResult.Failed,
        )
        assertEquals(0, backend.saves)
    }

    @Test fun createKeepsOffsetAndReportsScheduling() {
        val result =
            call(
                DeviceReminderTools.CREATE,
                """{"time":"2030-01-01T09:00:00+08:00","label":"Test"}""",
            ) as ToolExecutorResult.Completed
        val row =
            result.output.jsonObject
                .getValue("items")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(
            "saved",
            result.output.jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        assertEquals("APPROXIMATE", row.getValue("scheduling").jsonPrimitive.content)
        assertEquals("+08:00", backend.item.zone)
    }

    @Test fun createIdentityIsBoundToSession() {
        val args = """{"time":"2030-01-01T09:00:00+08:00","label":"Test"}"""
        call(DeviceReminderTools.CREATE, args)
        val first = backend.item.id
        call(DeviceReminderTools.CREATE, args)
        assertEquals(first, backend.item.id)
        call(DeviceReminderTools.CREATE, args, session = "b")
        assertNotEquals(first, backend.item.id)
    }

    @Test fun backendFailureIsNotSuccess() {
        backend.fail = true
        assertTrue(call(DeviceReminderTools.LIST) is ToolExecutorResult.Failed)
    }

    @Test fun descriptorsValidateAndSeparateReadFromMutation() {
        listOf(DeviceReminderTools.CREATE, DeviceReminderTools.LIST, DeviceReminderTools.CANCEL).forEach {
            assertEquals(it, DeviceReminderTools.descriptor(it).name.value)
        }
    }

    private class Fake : DeviceReminderTools.Backend {
        var item = LocalReminder("one", "Test", "", "2030-01-01T09:00", "+08:00", "ONCE", false, 1)
        var saves = 0
        var cancellations = 0
        var fail = false

        override suspend fun list(): List<LocalReminder> {
            check(!fail)
            return listOf(item)
        }

        override suspend fun save(reminder: LocalReminder): LocalReminder {
            saves++
            item = reminder.copy(revision = 1, scheduling = "APPROXIMATE")
            return item
        }

        override suspend fun cancel(
            id: String,
            revision: Long,
        ): Boolean {
            if (item.id != id || item.revision != revision || item.state != "ACTIVE") return false
            cancellations++
            item = item.copy(state = "CANCELLED")
            return true
        }
    }
}
