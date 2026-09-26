package com.helix.app.engine

import com.helix.core.model.AgentMode
import com.helix.core.storage.repository.ExpertProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExpertProfileSnapshotCodecTest {
    @Test
    fun `expert snapshot round trip is deterministic and contains behavior only`() {
        val profile =
            ExpertProfile(
                id = "expert",
                displayName = "Reviewer",
                instruction = "Review the implementation carefully.",
                recommendedSkillIds = listOf("skill-b", "skill-a"),
                recommendedConnectorIds = listOf("connector-a"),
                recommendedMode = AgentMode.PLAN,
            )

        val encoded = ExpertProfileSnapshotCodec.encode(profile)
        assertEquals(profile, ExpertProfileSnapshotCodec.decode(encoded))
        assertEquals(encoded, ExpertProfileSnapshotCodec.encode(profile))
        assertFalse(encoded.contains("permission", ignoreCase = true))
        assertFalse(encoded.contains("approval", ignoreCase = true))
    }
}
