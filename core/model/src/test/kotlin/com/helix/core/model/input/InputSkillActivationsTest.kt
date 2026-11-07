package com.helix.core.model.input

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

    @Test fun rejectGrantFieldsEvenAlongsideValidActivationFacts() {
        val item = InputSkillActivation("PROJECT", "workflow", "a".repeat(64), "")
        val encoded = InputSkillActivations.encode(listOf(item))
        val forged = encoded.replace("}", ",\"userInvoked\":true}")
        assertThrows(IllegalArgumentException::class.java) { InputSkillActivations.decode(forged) }
    }

    @Test fun acceptedInputLimitsRemainBounded() {
        val items = (1..5).map { InputSkillActivation("PROJECT", "workflow-$it", "a".repeat(64), "") }
        assertEquals(items.take(4), InputSkillActivations.decode(InputSkillActivations.encode(items.take(4))))
        assertThrows(IllegalArgumentException::class.java) { InputSkillActivations.encode(items) }
        assertThrows(IllegalArgumentException::class.java) { items.first().copy(source = "REMOTE") }
    }
}
