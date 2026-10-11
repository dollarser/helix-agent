package com.helix.core.storage.input

import com.helix.core.storage.repository.InputSkillActivation
import com.helix.core.storage.repository.InputSkillActivations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InputSkillActivationsTest {
    @Test fun exactContentAndArgumentsRoundTripWithoutGrantFields() {
        val item = InputSkillActivation("USER_IMPORTED", "workflow", "a".repeat(64), "中文\n参数")
        assertEquals(listOf(item), InputSkillActivations.decode(InputSkillActivations.encode(listOf(item))))
        assertThrows(IllegalArgumentException::class.java) { InputSkillActivations.decode("[{\"userInvoked\":true}]") }
        assertThrows(IllegalArgumentException::class.java) { InputSkillActivations.encode(listOf(item, item)) }
        assertThrows(IllegalArgumentException::class.java) { item.copy(arguments = "x".repeat(4097)) }
        assertThrows(IllegalArgumentException::class.java) { item.copy(snapshotHash = "latest") }
    }
}
