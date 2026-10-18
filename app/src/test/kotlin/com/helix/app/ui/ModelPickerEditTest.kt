package com.helix.app.ui

import com.helix.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelPickerEditTest {
    @Test fun changingModelResetsOnlyTheUncommittedReasoning() {
        val original = ModelPickerEdit("identity", true, "provider", "a", ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.OFF, original.select("provider", "b").reasoning)
        assertEquals(ReasoningEffort.OFF, original.select("other", "a").reasoning)
        assertEquals(ReasoningEffort.HIGH, original.reasoning)
        assertEquals(original, original.select("provider", "a"))
    }

    @Test fun identitySeparatesSessionsTargetsReasoningAndNullableFields() {
        val base = modelPickerIdentity("session", "provider", "a", ReasoningEffort.OFF)
        assertNotEquals(base, modelPickerIdentity("other", "provider", "a", ReasoningEffort.OFF))
        assertNotEquals(base, modelPickerIdentity("session", "provider", "b", ReasoningEffort.OFF))
        assertNotEquals(base, modelPickerIdentity("session", "provider", "a", ReasoningEffort.HIGH))
        assertNotEquals(
            modelPickerIdentity(null, "p", "a", ReasoningEffort.OFF),
            modelPickerIdentity("", "p", "a", ReasoningEffort.OFF),
        )
        assertNotEquals(
            modelPickerIdentity("a:b", "c", "d", ReasoningEffort.OFF),
            modelPickerIdentity("a", "b:c", "d", ReasoningEffort.OFF),
        )
    }
}
