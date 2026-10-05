package com.helix.extensions.mobileuse.tools

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationPauseReason
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationToolsTest {
    @Test
    fun timedOutWaitPreservesUncertainEffectAndValidOutput() {
        port.snapshotResult = successfulSnapshot()
        val result =
            completed(
                execute(
                    AutomationTools.WAIT,
                    buildJsonObject {
                        put("text", JsonPrimitive("not on screen"))
                        put("timeoutMillis", JsonPrimitive(1))
                    },
                ),
            )
        assertEquals("TIMED_OUT", result["waitStatus"]?.jsonPrimitive?.content)
        assertTrue(
            result
                .getValue("recoveryHint")
                .jsonPrimitive.content
                .contains("does not prove"),
        )
        assertTrue(port.nodeRequests.isEmpty())
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.WAIT }
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, result))
    }

    private val port = FakeAutomationPort()
    private val tools = AutomationTools(port)

    @Test
    fun progressContractRemainsAnApprovedExternalActionWithNumericValue() {
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.SET_PROGRESS }
        assertEquals(ToolOperationClass.EXTERNAL_ACTION, descriptor.operationClass)
        val input =
            buildJsonObject {
                put("token", JsonPrimitive(TOKEN))
                put("value", JsonPrimitive(25))
            }
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, input))
        assertTrue(
            ToolSchemaValidator.validate(
                descriptor.inputSchema,
                args("token" to TOKEN, "value" to "25"),
            ) is ToolSchemaValidation.Invalid,
        )
        completed(execute(AutomationTools.SET_PROGRESS, input))
        assertEquals(25.0, port.nodeRequests.single().progress)
        assertEquals(AutomationNodeAction.SET_PROGRESS, port.nodeRequests.single().action)
    }

    @Test
    fun waitReturnsImmediatelyWithRecoveryEvidenceInsteadOfPollingAPausedSession() {
        port.snapshotResult =
            AutomationSnapshotResult(
                AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED,
                pauseReason = AutomationPauseReason.TARGET_CHANGED,
                targetPackage = "com.other.app",
            )
        val result = completed(execute(AutomationTools.WAIT, args("text" to "Continue")))
        assertEquals(1, port.snapshots)
        assertEquals("com.other.app", result["targetPackage"]?.jsonPrimitive?.content)
        assertEquals("true", result["requiresAuthorization"]?.jsonPrimitive?.content)
        assertEquals("TARGET_CHANGED", result["pauseReason"]?.jsonPrimitive?.content)
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.WAIT }
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, result))
    }

    @Test
    fun registersExactTokenOnlySurfaceWithRiskAndCapability() {
        val descriptors = tools.descriptors()
        assertEquals(
            setOf(
                "ui.snapshot",
                "ui.find",
                "ui.click",
                "ui.click_match",
                "ui.long_click",
                "ui.set_text",
                "ui.ime_enter",
                "ui.set_progress",
                "ui.scroll",
                "ui.back",
                "ui.home",
                "ui.wait",
            ),
            descriptors.map { it.name.value }.toSet(),
        )
        descriptors.forEach {
            val expected =
                if (it.name.value in setOf(AutomationTools.BACK, AutomationTools.HOME)) {
                    Capability.ACCESSIBILITY_AUTOMATION
                } else {
                    Capability.MOBILE_USE
                }
            assertEquals(setOf(expected), it.requiredCapabilities)
        }
        assertTrue(descriptors.all { it.executionTarget == ExecutionTargetType.LOCAL_ANDROID })
        assertEquals(
            ToolOperationClass.READ_ONLY,
            descriptors
                .single {
                    it.name.value == AutomationTools.SNAPSHOT
                }.operationClass,
        )
        assertEquals(
            ToolOperationClass.READ_ONLY,
            descriptors.single { it.name.value == AutomationTools.WAIT }.operationClass,
        )
        assertEquals(
            ToolOperationClass.EXTERNAL_ACTION,
            descriptors
                .single {
                    it.name.value == AutomationTools.CLICK
                }.operationClass,
        )
        assertFalse(descriptors.any { it.inputSchema.toString().contains("coordinate", ignoreCase = true) })
    }

    @Test
    fun aHostPluginCanBindTheSameContractsToPluginProvenance() {
        val origin = ToolOrigin.PluginOrigin("mobile-use", "0.1.0", "mobile-use")
        val descriptors = AutomationTools(port, origin).descriptors()
        assertTrue(descriptors.all { it.origin == origin })
        assertTrue(
            descriptors.all {
                it.contractHash !=
                    tools.descriptors().single { base -> base.name == it.name }.contractHash
            },
        )
    }

    @Test
    fun unavailableSemanticsSuggestIndependentObservationButSensitiveUiDoesNot() {
        for (status in listOf(
            AutomationSnapshotStatus.SERVICE_NOT_CONNECTED,
            AutomationSnapshotStatus.NO_ACTIVE_SESSION,
            AutomationSnapshotStatus.UNSUPPORTED_UI,
        )) {
            port.snapshotResult = AutomationSnapshotResult(status)
            val snapshot = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
            assertTrue(
                snapshot
                    .getValue("recoveryHint")
                    .jsonPrimitive.content
                    .contains("ui.device"),
            )
        }
        for (status in listOf(AutomationSnapshotStatus.SENSITIVE_UI, AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED)) {
            port.snapshotResult = AutomationSnapshotResult(status)
            val snapshot = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
            assertFalse(
                snapshot["recoveryHint"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .contains("ui.gesture"),
            )
        }
    }

    @Test
    fun snapshotAndFindExposeOnlyIssuedNodeTokens() {
        port.snapshotResult = successfulSnapshot()
        val snapshot = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
        assertTrue(snapshot.toString().contains(TOKEN))
        val snapshotNode =
            snapshot
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("true", snapshotNode.getValue("canImeEnter").jsonPrimitive.content)
        assertEquals("true", snapshotNode.getValue("checkable").jsonPrimitive.content)
        assertEquals("true", snapshotNode.getValue("checked").jsonPrimitive.content)
        val found = completed(execute(AutomationTools.FIND, args("text" to "Continue")))
        assertEquals("FOUND", found["status"]?.jsonPrimitive?.content)
        assertTrue(found.toString().contains(TOKEN))
        assertEquals("ui.click", found.getValue("suggestedAction").jsonPrimitive.content)
        assertEquals(TOKEN, found.getValue("suggestedClickToken").jsonPrimitive.content)

        val checked =
            completed(
                execute(
                    AutomationTools.FIND,
                    buildJsonObject {
                        put("checkable", JsonPrimitive(true))
                        put("checked", JsonPrimitive(true))
                    },
                ),
            )
        assertEquals("FOUND", checked["status"]?.jsonPrimitive?.content)
        assertEquals(
            TOKEN,
            checked
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("token")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun incompleteSnapshotExplainsCaptureLimitAndPreservesUncheckedControl() {
        val original = successfulSnapshot()
        val tree = requireNotNull(original.snapshot)
        port.snapshotResult =
            original.copy(
                snapshot =
                    tree.copy(
                        nodes = tree.nodes.map { it.copy(checked = false) },
                        truncated = true,
                        truncationReasons = setOf("DEPTH_LIMIT"),
                    ),
            )
        val result = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
        assertEquals(
            "DEPTH_LIMIT",
            result
                .getValue("truncationReasons")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertTrue(
            result
                .getValue("recoveryHint")
                .jsonPrimitive.content
                .contains("cannot recover omitted nodes"),
        )
        val node =
            result
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("false", node.getValue("checked").jsonPrimitive.content)
        assertFalse(node.containsKey("className"))
        assertFalse(node.containsKey("parentToken"))
        assertEquals(TOKEN, node.getValue("clickTargetToken").jsonPrimitive.content)
        val missing = completed(execute(AutomationTools.FIND, args("text" to "missing.apk")))
        assertEquals("NOT_FOUND", missing.getValue("status").jsonPrimitive.content)
        assertEquals("true", missing.getValue("truncated").jsonPrimitive.content)
        assertTrue(
            missing
                .getValue("recoveryHint")
                .jsonPrimitive.content
                .contains("ui.screenshot"),
        )
    }

    @Test
    fun snapshotOmitsEmptyContainersButPreservesLabelsAndClickAncestors() {
        val original = clickableAncestorSnapshot()
        val tree = requireNotNull(original.snapshot)
        val container = tree.nodes.first().copy(token = "empty", clickable = false)
        port.snapshotResult = original.copy(snapshot = tree.copy(nodes = listOf(container) + tree.nodes))
        val nodes = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {})).getValue("nodes").jsonArray
        assertEquals(2, nodes.size)
        assertEquals(
            PARENT_TOKEN,
            nodes
                .last()
                .jsonObject
                .getValue("clickTargetToken")
                .jsonPrimitive.content,
        )
        assertEquals(
            tree.nodes,
            port.snapshotResult.snapshot
                ?.nodes
                ?.drop(1),
        )
    }

    @Test
    fun findReturnsExplicitClickableAncestorWithoutImplicitlyClickingIt() {
        port.snapshotResult = clickableAncestorSnapshot()
        val found = completed(execute(AutomationTools.FIND, args("text" to "weixin.qq.com")))
        val node =
            found
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(CHILD_TOKEN, node.getValue("token").jsonPrimitive.content)
        assertEquals(PARENT_TOKEN, node.getValue("clickTargetToken").jsonPrimitive.content)
        assertEquals(PARENT_TOKEN, found.getValue("suggestedClickToken").jsonPrimitive.content)
        assertFalse(
            node
                .getValue("clickable")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test
    fun findAndWaitDescriptorsExposeClickSuggestionContract() {
        val descriptors = tools.descriptors().associateBy { it.name.value }
        assertEquals(8, descriptors.getValue(AutomationTools.FIND).version.value)
        assertEquals(8, descriptors.getValue(AutomationTools.WAIT).version.value)
        assertTrue(
            descriptors
                .getValue(AutomationTools.FIND)
                .outputSchema
                .toString()
                .contains("suggestedClickToken"),
        )
        assertTrue(descriptors.getValue(AutomationTools.WAIT).description.contains("suggestedClickToken"))
    }

    @Test
    fun clickMatchAtomicallyUsesTheUniqueFreshClickableTarget() {
        port.snapshotResult = successfulSnapshot()
        completed(execute(AutomationTools.CLICK_MATCH, args("text" to "Continue")))
        assertEquals(1, port.snapshots)
        assertEquals(AutomationNodeAction.CLICK, port.nodeRequests.single().action)
        assertEquals(TOKEN, port.nodeRequests.single().token)
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.CLICK_MATCH }
        assertTrue(descriptor.description.contains("system Install button"))
        assertTrue(descriptor.description.contains("human click"))
        assertEquals(7, descriptor.version.value)
        assertTrue(descriptor.inputSchema.toString().contains("timeoutMillis"))
        assertEquals(65L, descriptor.timeout.inWholeSeconds)
    }

    @Test
    fun modelCannotOverrideTheSelectedBackend() {
        val result =
            execute(
                AutomationTools.CLICK_MATCH,
                args("backend" to "shizuku", "packageName" to "com.example.app"),
            ) as ToolExecutorResult.Failed
        assertEquals("BACKEND_IS_HOST_SELECTED", result.detail)
        assertTrue(result.sideEffectFree)
        assertTrue(port.nodeRequests.isEmpty())
        assertEquals(0, port.snapshots)
    }

    @Test
    fun clickMatchCanWaitForAControlAndClickTheFreshMatch() {
        port.snapshotResults += notFoundSnapshot()
        port.snapshotResults += successfulSnapshot()
        completed(
            execute(
                AutomationTools.CLICK_MATCH,
                buildJsonObject {
                    put("text", JsonPrimitive("Continue"))
                    put("timeoutMillis", JsonPrimitive(500))
                    put("pollMillis", JsonPrimitive(50))
                },
            ),
        )
        assertEquals(2, port.snapshots)
        assertEquals(TOKEN, port.nodeRequests.single().token)
    }

    @Test
    fun clickMatchUsesOneSharedClickableAncestorButRefusesDistinctAmbiguity() {
        port.snapshotResult = clickableAncestorSnapshot()
        completed(execute(AutomationTools.CLICK_MATCH, args("text" to "weixin.qq.com")))
        assertEquals(PARENT_TOKEN, port.nodeRequests.single().token)

        port.nodeRequests.clear()
        port.snapshotResult = ambiguousClickableSnapshot()
        val ambiguous = execute(AutomationTools.CLICK_MATCH, args("className" to "Button")) as ToolExecutorResult.Failed
        assertEquals("TARGET_AMBIGUOUS", ambiguous.detail)
        assertTrue(ambiguous.sideEffectFree)
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test
    fun clickMatchRefusesMissingAndNonClickableTargetsWithoutSideEffects() {
        port.snapshotResult = successfulSnapshot()
        val missing = execute(AutomationTools.CLICK_MATCH, args("text" to "Missing")) as ToolExecutorResult.Failed
        assertEquals("TARGET_NOT_FOUND", missing.detail)
        assertTrue(missing.sideEffectFree)

        port.snapshotResult = nonClickableSnapshot()
        val inert = execute(AutomationTools.CLICK_MATCH, args("text" to "Label")) as ToolExecutorResult.Failed
        assertEquals("TARGET_NOT_CLICKABLE", inert.detail)
        assertTrue(inert.sideEffectFree)
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test
    fun actionsForwardTokensAndStableRefusalsNeverBecomeSuccess() {
        val output = completed(execute(AutomationTools.CLICK, args("token" to TOKEN)))
        assertEquals(AutomationNodeAction.CLICK, port.nodeRequests.single().action)
        assertEquals(TOKEN, port.nodeRequests.single().token)
        assertTrue(
            output
                .getValue("actionHint")
                .jsonPrimitive.content
                .contains("observe"),
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                tools.descriptors().single { it.name.value == AutomationTools.CLICK }.outputSchema,
                output,
            ),
        )

        port.actionResult = AutomationActionResult(AutomationActionStatus.STALE_TOKEN)
        val failed = execute(AutomationTools.CLICK, args("token" to TOKEN)) as ToolExecutorResult.Failed
        assertEquals("STALE_TOKEN", failed.detail)
        assertTrue(failed.sideEffectFree)
        assertFalse(failed.requiresReview)
    }

    @Test
    fun uncertainPlatformOutcomesNeverAdvertiseSafeTechnicalRetry() {
        for (status in listOf(AutomationActionStatus.ACTION_FAILED, AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)) {
            port.actionResult = AutomationActionResult(status)
            for (name in listOf(AutomationTools.CLICK, AutomationTools.BACK, AutomationTools.HOME)) {
                val result = execute(name, args("token" to TOKEN)) as ToolExecutorResult.Failed
                assertEquals(status.name, result.detail)
                assertFalse(result.sideEffectFree)
                assertTrue(result.requiresReview)
            }
        }
    }

    @Test
    fun setTextAndScrollHaveTypedArgumentsAndNoBlindCoordinates() {
        execute(AutomationTools.SET_TEXT, args("token" to TOKEN, "text" to "hello"))
        assertEquals("hello", port.nodeRequests.last().text)
        execute(
            AutomationTools.SET_TEXT,
            buildJsonObject {
                put("token", JsonPrimitive(TOKEN))
                put("text", JsonPrimitive("query"))
                put("submit", JsonPrimitive(true))
            },
        )
        assertTrue(port.nodeRequests.last().submit)
        execute(AutomationTools.IME_ENTER, args("token" to TOKEN))
        assertEquals(AutomationNodeAction.IME_ENTER, port.nodeRequests.last().action)
        execute(AutomationTools.SCROLL, args("token" to TOKEN, "direction" to "forward"))
        assertEquals(AutomationNodeAction.SCROLL_FORWARD, port.nodeRequests.last().action)
        assertTrue(
            execute(
                AutomationTools.SCROLL,
                args("token" to TOKEN, "direction" to "diagonal"),
            ) is ToolExecutorResult.Failed,
        )
    }

    @Test
    fun globalActionsRemainExplicitAndBounded() {
        execute(AutomationTools.BACK, buildJsonObject {})
        execute(AutomationTools.HOME, buildJsonObject {})
        assertEquals(listOf(AutomationGlobalAction.BACK, AutomationGlobalAction.HOME), port.globalRequests)
    }

    @Test fun scrollSchemaOnlyAdmitsDirectionsTheExecutorImplements() {
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.SCROLL }
        assertEquals(3, descriptor.version.value)
        val schema = descriptor.inputSchema
        listOf("forward", "backward").forEach {
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(
                    schema,
                    args(
                        "token" to TOKEN,
                        "direction" to it,
                    ),
                ),
            )
        }
        listOf("up", "down", "UP", "left", "right", "next").forEach {
            assertTrue(
                ToolSchemaValidator.validate(
                    schema,
                    args("token" to TOKEN, "direction" to it),
                ) is ToolSchemaValidation.Invalid,
            )
        }
        val direction =
            schema
                .getValue("properties")
                .jsonObject
                .getValue("direction")
                .jsonObject
        assertEquals(
            kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("forward"), JsonPrimitive("backward"))),
            direction["enum"],
        )
        execute(AutomationTools.SCROLL, args("token" to TOKEN, "direction" to "backward"))
        assertEquals(AutomationNodeAction.SCROLL_BACKWARD, port.nodeRequests.last().action)
    }

    private fun execute(
        name: String,
        args: JsonObject,
    ) = tools.executor(name).execute(
        ExecutableToolCall(
            "call",
            name,
            "1",
            args,
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(15),
            NoCancellation,
        ),
    )

    private fun completed(result: ToolExecutorResult) = (result as ToolExecutorResult.Completed).output.jsonObject

    private fun args(vararg pairs: Pair<String, String>) =
        buildJsonObject {
            pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }

    private fun successfulSnapshot() =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "com.example.fixture",
                4,
                7,
                Instant.EPOCH,
                listOf(
                    AutomationSnapshotNode(
                        TOKEN,
                        null,
                        0,
                        "Button",
                        "Continue",
                        null,
                        "continue",
                        AutomationNodeBounds(0, 0, 10, 10),
                        true,
                        false,
                        false,
                        false,
                        true,
                        canImeEnter = true,
                        checkable = true,
                        checked = true,
                    ),
                ),
                false,
            ),
        )

    private fun notFoundSnapshot() =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "com.example.fixture",
                4,
                6,
                Instant.EPOCH,
                emptyList(),
                false,
            ),
        )

    private fun clickableAncestorSnapshot() =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "com.android.chrome",
                7,
                9,
                Instant.EPOCH,
                listOf(
                    AutomationSnapshotNode(
                        PARENT_TOKEN,
                        null,
                        0,
                        "android.view.ViewGroup",
                        null,
                        null,
                        null,
                        AutomationNodeBounds(0, 0, 100, 40),
                        true,
                        false,
                        false,
                        false,
                        true,
                    ),
                    AutomationSnapshotNode(
                        CHILD_TOKEN,
                        PARENT_TOKEN,
                        1,
                        "android.widget.TextView",
                        "weixin.qq.com",
                        null,
                        null,
                        AutomationNodeBounds(0, 0, 100, 40),
                        false,
                        false,
                        false,
                        false,
                        true,
                    ),
                ),
                false,
            ),
        )

    private fun ambiguousClickableSnapshot() =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "com.example.fixture",
                4,
                8,
                Instant.EPOCH,
                listOf(
                    AutomationSnapshotNode(
                        TOKEN,
                        null,
                        0,
                        "Button",
                        "First",
                        null,
                        "first",
                        AutomationNodeBounds(0, 0, 10, 10),
                        true,
                        false,
                        false,
                        false,
                        true,
                    ),
                    AutomationSnapshotNode(
                        SECOND_TOKEN,
                        null,
                        0,
                        "Button",
                        "Second",
                        null,
                        "second",
                        AutomationNodeBounds(20, 0, 30, 10),
                        true,
                        false,
                        false,
                        false,
                        true,
                    ),
                ),
                false,
            ),
        )

    private fun nonClickableSnapshot() =
        AutomationSnapshotResult(
            AutomationSnapshotStatus.SUCCESS,
            AutomationSnapshot(
                "com.example.fixture",
                4,
                9,
                Instant.EPOCH,
                listOf(
                    AutomationSnapshotNode(
                        TOKEN,
                        null,
                        0,
                        "TextView",
                        "Label",
                        null,
                        "label",
                        AutomationNodeBounds(0, 0, 10, 10),
                        false,
                        false,
                        false,
                        false,
                        true,
                    ),
                ),
                false,
            ),
        )

    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
        private const val PARENT_TOKEN = "11111111111111111111111111111111"
        private const val CHILD_TOKEN = "22222222222222222222222222222222"
        private const val SECOND_TOKEN = "33333333333333333333333333333333"
    }
}

private class FakeAutomationPort : AutomationToolPort {
    var snapshotResult = AutomationSnapshotResult(AutomationSnapshotStatus.NO_ACTIVE_SESSION)
    val snapshotResults = ArrayDeque<AutomationSnapshotResult>()
    var actionResult = AutomationActionResult(AutomationActionStatus.SUCCEEDED)
    val nodeRequests = mutableListOf<AutomationNodeActionRequest>()
    val globalRequests = mutableListOf<AutomationGlobalAction>()

    var snapshots = 0

    override fun snapshot(): AutomationSnapshotResult {
        snapshots++
        return if (snapshotResults.isNotEmpty()) snapshotResults.removeFirst() else snapshotResult
    }

    override fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult {
        nodeRequests += request
        return actionResult
    }

    override fun globalAction(action: AutomationGlobalAction): AutomationActionResult {
        globalRequests += action
        return actionResult
    }
}
