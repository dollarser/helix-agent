package com.helix.extensions.mobileuse

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class MobileUseToolContractsTest {
    private val descriptor =
        ToolDescriptor(
            ToolName("ui.snapshot"),
            ToolVersion(1),
            "Observe UI",
            buildJsonObject {},
            buildJsonObject {},
            ToolOperationClass.READ_ONLY,
            10.seconds,
            1024,
            emptySet(),
            Idempotency.IDEMPOTENT,
            ExecutionTargetType.LOCAL_ANDROID,
            ToolOrigin.PluginOrigin("mobile-use", "1", "mobile-use"),
        )
    private val contracts = MobileUseToolContracts(listOf(descriptor))

    @Test fun packagedSkillExplainsObservationRecoveryAndPermissionBoundaries() {
        val root = java.io.File("src/main/assets/plugins/mobile-use/skills/android-ui-task")
        val entry = root.resolve("SKILL.md").readText()
        assertTrue(entry.toByteArray().size < 1024)
        assertTrue(entry.contains("Observe the authorized target"))
        assertTrue(entry.contains("Launch only if it is not already open"))
        assertTrue(entry.contains("references/android-ui-reference.md"))
        val text = entry + root.resolve("references/android-ui-reference.md").readText()
        assertTrue(text.contains("name: android-ui-task"))
        assertTrue(text.contains("ui.wait"))
        assertTrue(text.contains("ACTION_OUTCOME_UNKNOWN"))
        assertTrue(text.contains("does not grant permission"))
    }

    @Test fun requiredGuidanceKeepsImmediateBoundariesAndDelegatesWorkflowDetails() {
        val root = java.io.File("src/main/assets/plugins/mobile-use")
        val required = root.resolve("required.md").readText()
        val reference = root.resolve("skills/android-ui-task/references/android-ui-reference.md").readText()
        assertTrue(required.toByteArray().size < 2048)
        listOf(
            "Read android-ui-task",
            "ACTION_OUTCOME_UNKNOWN",
            "actual attached pixels",
            "originatingHelixPackage",
            "background mode",
        ).forEach { assertTrue("Missing immediate boundary: $it", required.contains(it)) }
        listOf(
            "submit=true",
            "canImeEnter=true",
            "scrollDirections",
            "text and editableValue as empty",
            "ACTION_OUTCOME_UNKNOWN",
            "ui.click_match",
            "pollMillis",
        ).forEach { assertTrue("Missing workflow detail: $it", reference.contains(it)) }
        assertFalse(required.contains("submit=true"))
        assertFalse(required.contains("pollMillis"))
    }

    @Test fun onlyTheOwnedContractReceivesMobileUseRouting() {
        assertTrue(contracts.owns(descriptor.copy()))
        assertFalse(contracts.owns(null))
        assertFalse(contracts.owns(descriptor.copy(name = ToolName("ui.unregistered"))))
        assertFalse(contracts.owns(descriptor.copy(origin = ToolOrigin.BuiltInOrigin)))
        assertFalse(contracts.owns(descriptor.copy(origin = ToolOrigin.PluginOrigin("other", "1", "mobile-use"))))
        assertFalse(contracts.owns(descriptor.copy(version = ToolVersion(2))))
        assertFalse(contracts.owns(descriptor.copy(timeout = 20.seconds)))
    }

    @Test fun readinessNamesMustBelongToThePublishedContractSet() {
        assertTrue(contracts.containsName("ui.snapshot"))
        assertFalse(contracts.containsName("ui.unregistered"))
        assertFalse(contracts.containsName(null))
    }
}
