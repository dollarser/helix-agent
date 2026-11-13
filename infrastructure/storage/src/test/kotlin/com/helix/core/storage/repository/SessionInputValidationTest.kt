package com.helix.core.storage.repository

import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.InputSkillActivation
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.model.input.SessionInputSpec
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionInputValidationTest {
    private val empty =
        SessionInputSpec(
            "input",
            "session",
            SessionInputDelivery.QUEUE,
            null,
            0,
            "",
            emptyList(),
            InputConfiguration("provider", "model", "ACT", "fingerprint"),
            0,
        )
    private val skill = InputSkillActivation("BUILT_IN", "review", "a".repeat(64), "")

    @Test fun delegationNeverCarriesSkillAttachmentReferenceOrSelfAuthority() {
        val origin =
            com.helix.core.model.input
                .ModelDelegationOrigin("source", "turn", "call", "key", 1)
        val delegated = empty.copy(text = "task", delegation = origin)
        SessionInputValidation.validate(delegated)
        SessionInputValidation.validate(
            delegated.copy(delivery = SessionInputDelivery.STEER, expectedTurnId = "target"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SessionInputValidation.validate(delegated.copy(skillActivations = listOf(skill)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionInputValidation.validate(delegated.copy(sessionId = "source"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionInputValidation.validate(
                delegated.copy(delivery = SessionInputDelivery.STEER, expectedTurnId = null),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            origin.copy(grantRevision = 0)
        }
    }

    @Test fun plainEmptyInputIsStillRejected() {
        assertThrows(IllegalArgumentException::class.java) { SessionInputValidation.validate(empty) }
        assertThrows(IllegalArgumentException::class.java) { SessionInputValidation.validate(empty.copy(text = " ")) }
    }

    @Test fun explicitSkillOnlyInputIsContentInActAndGoalModes() {
        SessionInputValidation.validate(empty.copy(skillActivations = listOf(skill)))
        SessionInputValidation.validate(
            empty.copy(
                configuration = empty.configuration.copy(mode = "GOAL"),
                skillActivations = listOf(skill),
            ),
        )
    }

    @Test fun skillContentDoesNotBypassShapeOrIdentityValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionInputValidation.validate(empty.copy(skillActivations = listOf(skill, skill)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionInputValidation.validate(empty.copy(text = "\u0000", skillActivations = listOf(skill)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionInputValidation.validate(empty.copy(sessionId = "", skillActivations = listOf(skill)))
        }
    }
}
