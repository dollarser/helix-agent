package com.helix.app.chat

import com.helix.app.goal.GoalLifecycleTools
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ModelToolExposureOrderTest {
    private val uiNames =
        setOf(
            "ui_apps",
            "ui_launch",
            "ui_snapshot",
            "ui_find",
            "ui_click",
            "ui_click_match",
            "ui_set_text",
            "ui_scroll",
            "ui_back",
            "ui_wait",
            "ui_device",
            "ui_screenshot",
            "ui_gesture",
            "device_open_url",
        )

    @Test fun essentialBuiltInsSurviveTheModelToolLimit() {
        val optional = (0 until 80).map { descriptor("optional_$it") }
        val essential =
            listOf(
                descriptor("write"),
                descriptor("read"),
                descriptor("edit"),
                descriptor("files_list"),
                descriptor("files_stat"),
                descriptor("files_mkdir"),
                descriptor("glob"),
                descriptor("tools_search"),
                descriptor("goal_report"),
            )
        val exposed = ModelToolExposureOrder.prioritize(optional + essential)
        val names = exposed.map { it.name.value }
        essential.forEach { assertTrue("${it.name.value} must survive truncation", it.name.value in names) }
        assertEquals((optional + essential).toSet(), exposed.toSet())
    }

    @Test fun optionalToolsUseStableNamesWithinTheirPriorityRank() {
        val tools = listOf(descriptor("optional_z"), descriptor("write"), descriptor("optional_a"))
        assertEquals(
            listOf("write", "optional_a", "optional_z"),
            ModelToolExposureOrder.prioritize(tools).map { it.name.value },
        )
    }

    @Test fun discoveryRecencyPermutationsProduceIdenticalProviderRequests() {
        val tools =
            listOf(
                "mcp_fixture_z",
                "read",
                "goal_report",
                "mcp_fixture_a",
                "ask_user",
                "ui_snapshot",
            ).map(::descriptor)
        val preferred = setOf("ui_snapshot", "mcp_fixture_z")
        val encoders =
            listOf(
                com.helix.provider.openai.responses
                    .ResponsesRequestEncoder { error("no images") },
                com.helix.provider.openai.chat
                    .ChatCompletionsRequestEncoder { error("no images") },
                com.helix.provider.anthropic
                    .AnthropicRequestEncoder { error("no images") },
            )
        for (encoder in encoders) {
            fun wire(input: List<ToolDescriptor>): String =
                encoder.encode(
                    com.helix.core.model.ModelRequest(
                        model = "fixture",
                        messages =
                            listOf(
                                com.helix.core.model
                                    .ModelMessage(com.helix.core.model.ModelRole.USER, "Read"),
                            ),
                        tools =
                            ModelToolExposureOrder
                                .prioritize(input, preferred)
                                .map(com.helix.app.application.tools.FileToolArguments::modelSchema)
                                .map(com.helix.app.application.tools.ToolPresentationMetadata::augment),
                        maxOutputTokens = 1024,
                    ),
                )
            val expected = wire(tools)
            repeat(64) { seed ->
                assertEquals(expected, wire(tools.shuffled(kotlin.random.Random(seed))))
            }
        }
    }

    @Test fun removalReadditionAndPreferenceChangesRemainLive() {
        val tools = listOf("optional_z", "optional_a", "read").map(::descriptor)
        val expected = ModelToolExposureOrder.prioritize(tools)
        val removed = ModelToolExposureOrder.prioritize(tools.dropLast(1), setOf("read"))
        assertTrue(removed.none { it.name.value == "read" })
        assertEquals(expected, ModelToolExposureOrder.prioritize(removed + tools.last()))
        assertEquals(
            listOf("read", "optional_z", "optional_a"),
            ModelToolExposureOrder.prioritize(tools, setOf("optional_z")).map { it.name.value },
        )
    }

    @Test fun tokenActionsKeepTheirSnapshotAndNavigationContractsUnderCrowding() {
        val ui =
            listOf(
                "ui_snapshot",
                "ui_find",
                "ui_click",
                "ui_click_match",
                "ui_set_text",
                "ui_ime_enter",
                "ui_scroll",
                "ui_back",
                "ui_wait",
                "ui_device",
                "ui_screenshot",
                "ui_gesture",
                "device_open_url",
                "http_fetch",
            ).map(::descriptor)
        val crowded = (0 until 80).map { descriptor("optional_$it") } + ui + descriptor("write")
        val exposed =
            ModelToolExposureOrder
                .prioritize(
                    crowded,
                    preferred =
                        ui
                            .map {
                                it.name.value
                            }.toSet(),
                )
        assertTrue(exposed.containsAll(ui))
        assertTrue(
            exposed.none {
                it.name.value in setOf("ui_long_click", "ui_set_progress", "ui_home")
            },
        )
        assertTrue(exposed.any { it.name.value == "write" })
        assertEquals(crowded.toSet(), exposed.toSet())
    }

    @Test fun priorityNeverReintroducesAnUnavailableContract() {
        val admitted = listOf(descriptor("ui_back"), descriptor("read"))
        assertEquals(admitted.toSet(), ModelToolExposureOrder.prioritize(admitted, preferred = uiNames).toSet())
    }

    @Test fun withoutAnActiveAutomationSessionUiDoesNotDisplaceOtherTools() {
        val tools = (0 until 64).map { descriptor("optional_$it") } + descriptor("ui_snapshot")
        assertEquals(tools.sortedBy { it.name.value }, ModelToolExposureOrder.prioritize(tools))
    }

    @Test fun defaultSurfacePreservesCompleteWorkflowAndLeavesOptionalToolsDiscoverable() {
        val names = ModelToolExposureOrder.defaultNames(preferred = uiNames)
        assertTrue(
            names.containsAll(
                listOf(
                    "read",
                    "write",
                    "edit",
                    "ui_snapshot",
                    "ui_apps",
                    "ui_find",
                    "ui_click_match",
                    "ui_launch",
                    "ui_wait",
                    "ui_device",
                    "ui_screenshot",
                    "ui_gesture",
                    "device_open_url",
                ),
            ),
        )
        assertTrue("code_linux_run" !in names)
        assertTrue("http_fetch" !in names)
        assertTrue("code_javascript_run" !in names)
        val nonUi = ModelToolExposureOrder.defaultNames(emptySet())
        assertTrue("ui_snapshot" !in nonUi)
        assertTrue("device_open_url" !in nonUi)
        assertTrue("code_javascript_run" in nonUi)
        assertTrue("http_fetch" !in nonUi)
        assertTrue("skills_list" !in names)
        assertTrue("skills_disable" !in names)
        assertTrue(names.none { it.startsWith("skills_") || it in GoalLifecycleTools.names })
    }

    @Test fun goalSurfaceTracksTheSessionAndTheLiveRoundWithoutInferringTextIntent() {
        fun names(
            bound: Boolean,
            unfinished: Boolean,
            goalMode: Boolean,
        ) = ModelToolExposureOrder
            .defaultNames(
                emptySet(),
                goalNames = GoalLifecycleTools.directNames(bound, unfinished, goalMode),
            ).intersect(GoalLifecycleTools.names)

        assertEquals(emptySet<String>(), names(false, false, false))
        assertEquals(setOf("goal_get", "goal_create"), names(false, false, true))
        assertEquals(setOf("goal_get", "goal_update"), names(false, true, false))
        assertEquals(setOf("goal_get", "goal_update"), names(false, true, true))
        assertEquals(setOf("goal_get", "goal_update", "goal_report"), names(true, true, false))
    }

    @Test fun directSkillContractsDoNotEnableManagementOrDisplaceNormalTools() {
        val skillReads = setOf("skills_read", "skills_read_resource")
        val base = ModelToolExposureOrder.defaultNames(emptySet())
        val withSkills = ModelToolExposureOrder.defaultNames(emptySet(), skillNames = skillReads)
        assertEquals(base + skillReads, withSkills)
        assertTrue("skills_enable" !in withSkills)
    }

    private fun descriptor(name: String) =
        ToolDescriptor(
            name = ToolName(name),
            version = ToolVersion(1),
            description = name,
            inputSchema =
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                },
            outputSchema =
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                },
            operationClass = if (name.startsWith("mcp_")) ToolOperationClass.NETWORK else ToolOperationClass.READ_ONLY,
            timeout = 1.seconds,
            maxOutputBytes = 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin =
                if (name.startsWith("mcp_")) {
                    ToolOrigin.McpOrigin("fixture", "2025-11-25", "a".repeat(64))
                } else {
                    ToolOrigin.BuiltInOrigin
                },
        )
}
