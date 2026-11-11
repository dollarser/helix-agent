package com.helix.extensions.mobileuse.tools

import com.helix.core.model.CancelSignal
import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
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
    @Test fun longClickCapabilitySuppliesItsActualToolAndParentTokenWithoutExecutingIt() {
        val original = clickableAncestorSnapshot()
        val tree = requireNotNull(original.snapshot)
        port.snapshotResult =
            original.copy(
                snapshot =
                    tree.copy(
                        nodes =
                            tree.nodes.map {
                                if (it.token == PARENT_TOKEN) it.copy(longClickable = true) else it
                            },
                    ),
            )
        val found = completed(execute(AutomationTools.FIND, args("text" to "weixin.qq.com")))
        val node =
            found
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(
            "ui.long_click",
            node
                .getValue("longClick")
                .jsonObject
                .getValue("tool")
                .jsonPrimitive.content,
        )
        assertEquals(
            PARENT_TOKEN,
            node
                .getValue("longClick")
                .jsonObject
                .getValue("token")
                .jsonPrimitive.content,
        )
        assertTrue(port.nodeRequests.isEmpty())
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                tools.descriptors().single { it.name.value == AutomationTools.FIND }.outputSchema,
                found,
            ),
        )
        for (name in listOf(AutomationTools.SNAPSHOT, AutomationTools.FIND)) {
            assertTrue(
                tools.descriptors().single { it.name.value == name }.discoveryCompanions.any {
                    it.value == AutomationTools.LONG_CLICK
                },
            )
        }
    }

    @Test fun outputLimitIsDistinctFromCollectionAndFindCanReachTheOmittedNode() {
        val original = successfulSnapshot()
        val tree = requireNotNull(original.snapshot)
        val nodes = (0..210).map { tree.nodes.single().copy(token = "node$it", text = "Item $it") }
        port.snapshotResult = original.copy(snapshot = tree.copy(nodes = nodes))
        val projected = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
        assertEquals("211", projected.getValue("capturedNodeCount").jsonPrimitive.content)
        assertEquals("200", projected.getValue("returnedNodeCount").jsonPrimitive.content)
        assertEquals(
            "OUTPUT_LIMIT",
            projected
                .getValue("truncationReasons")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertEquals("true", projected.getValue("truncated").jsonPrimitive.content)
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                tools
                    .descriptors()
                    .single {
                        it.name.value == AutomationTools.SNAPSHOT
                    }.outputSchema,
                projected,
            ),
        )
        val found = completed(execute(AutomationTools.FIND, args("text" to "Item 210")))
        assertEquals("FOUND", found.getValue("status").jsonPrimitive.content)
        assertEquals("false", found.getValue("truncated").jsonPrimitive.content)
    }

    @Test fun allCaptureTruncationReasonsFitTheDeclaredOutputSchema() {
        val original = successfulSnapshot()
        val reasons = com.helix.extensions.mobileuse.automation.SnapshotTruncationReasons.all
        port.snapshotResult =
            original.copy(
                snapshot = requireNotNull(original.snapshot).copy(truncated = true, truncationReasons = reasons),
            )
        val result = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
        assertEquals(
            reasons,
            result
                .getValue("truncationReasons")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet(),
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                tools
                    .descriptors()
                    .single {
                        it.name.value == AutomationTools.SNAPSHOT
                    }.outputSchema,
                result,
            ),
        )
    }

    @Test fun pollingIsOptionalDocumentedAndStrictlyBounded() {
        for (name in listOf(AutomationTools.WAIT, AutomationTools.CLICK_MATCH)) {
            val descriptor = tools.descriptors().single { it.name.value == name }
            val properties = descriptor.inputSchema.getValue("properties").jsonObject
            val poll = properties.getValue("pollMillis").jsonObject
            assertEquals("100", poll.getValue("default").jsonPrimitive.content)
            assertTrue(
                poll
                    .getValue("description")
                    .jsonPrimitive.content
                    .contains("Normally omit"),
            )
            for (value in listOf(50, 1000)) {
                assertEquals(
                    ToolSchemaValidation.Valid,
                    ToolSchemaValidator.validate(
                        descriptor.inputSchema,
                        buildJsonObject {
                            put("text", JsonPrimitive("Continue"))
                            put("pollMillis", JsonPrimitive(value))
                        },
                    ),
                )
            }
            for (value in listOf(49, 1001, 2000)) {
                assertTrue(
                    ToolSchemaValidator.validate(
                        descriptor.inputSchema,
                        buildJsonObject {
                            put("text", JsonPrimitive("Continue"))
                            put("pollMillis", JsonPrimitive(value))
                        },
                    )
                        is ToolSchemaValidation.Invalid,
                )
            }
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(
                    descriptor.inputSchema,
                    args("text" to "Continue"),
                ),
            )
        }
    }

    @Test
    fun cancellationAfterObservationConfirmsWaitHasNoSideEffects() {
        port.snapshotResult = successfulSnapshot()
        val result =
            execute(
                AutomationTools.WAIT,
                args("text" to "not on screen"),
                object : CancelSignal {
                    override fun isCancelled() = port.snapshots > 0
                },
            ) as ToolExecutorResult.CancelledWithEffectTruth
        assertEquals(1, port.snapshots)
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
        assertEquals("AUTOMATION_WAIT_CANCELLED", result.detail)
        assertTrue(port.nodeRequests.isEmpty())
        assertTrue(port.globalRequests.isEmpty())
    }

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
        assertFalse(result.containsKey("matchCount"))
        assertFalse(result.containsKey("suggestedClickToken"))
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

    @Test fun installerVisibilityHintNeverOverridesProtectedFields() {
        val original = successfulSnapshot()
        val tree = requireNotNull(original.snapshot).copy(packageName = "com.android.packageinstaller")
        port.snapshotResult = original.copy(snapshot = tree, backend = "accessibility")
        val hint = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))["recoveryHint"]
        assertTrue(hint.toString().contains("ordinary Accessibility"))
        port.snapshotResult =
            original.copy(
                snapshot =
                    tree.copy(nodes = tree.nodes.map { it.copy(redacted = true) }),
                backend = "accessibility",
            )
        val protected = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))["recoveryHint"]
        assertFalse(protected.toString().contains("ordinary Accessibility"))
        assertTrue(protected.toString().contains("protected", ignoreCase = true))
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
            val expected = Capability.MOBILE_USE
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
    fun disconnectedSnapshotAndClickMatchShareActionableRecoveryWithoutDispatch() {
        port.snapshotResult = AutomationSnapshotResult(AutomationSnapshotStatus.SERVICE_NOT_CONNECTED)
        val snapshot = completed(execute(AutomationTools.SNAPSHOT, buildJsonObject {}))
        val hint = snapshot.getValue("recoveryHint").jsonPrimitive.content
        assertTrue(hint.contains("Helix System permissions"))
        assertTrue(hint.contains("in Android settings"))
        assertTrue(hint.contains("fresh observation"))
        assertTrue(hint.contains("already authorized"))
        val click = execute(AutomationTools.CLICK_MATCH, args("text" to "Save")) as ToolExecutorResult.Failed
        assertEquals("SERVICE_NOT_CONNECTED: $hint", click.detail)
        assertTrue(click.sideEffectFree)
        assertTrue(port.nodeRequests.isEmpty())
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
    fun cappedFindDoesNotHideAmbiguityOrSuggestAnUnprovenUniqueClick() {
        val result = successfulSnapshot()
        val snapshot = requireNotNull(result.snapshot)
        val node = snapshot.nodes.single()
        port.snapshotResult = result.copy(snapshot = snapshot.copy(nodes = listOf(node, node.copy(token = "second"))))
        val found =
            completed(
                execute(
                    AutomationTools.FIND,
                    buildJsonObject {
                        put("text", JsonPrimitive("Continue"))
                        put("maxResults", JsonPrimitive(1))
                    },
                ),
            )
        assertEquals("2", found.getValue("matchCount").jsonPrimitive.content)
        assertEquals("true", found.getValue("ambiguous").jsonPrimitive.content)
        assertEquals("true", found.getValue("truncated").jsonPrimitive.content)
        assertFalse(found.containsKey("suggestedClickToken"))
        assertEquals(
            snapshot.createdAt.toEpochMilli().toString(),
            found.getValue("observedAtMillis").jsonPrimitive.content,
        )
    }

    @Test
    fun waitResultLimitDoesNotImplyUniqueClickableAncestor() {
        val result = clickableAncestorSnapshot()
        val snapshot = requireNotNull(result.snapshot)
        val parent = snapshot.nodes.first { it.token == PARENT_TOKEN }
        val child = snapshot.nodes.first { it.token == CHILD_TOKEN }
        val otherParent = parent.copy(token = SECOND_TOKEN)
        val otherChild = child.copy(token = TOKEN, parentToken = SECOND_TOKEN)
        port.snapshotResult = result.copy(snapshot = snapshot.copy(nodes = snapshot.nodes + otherParent + otherChild))
        val output =
            completed(
                execute(
                    AutomationTools.WAIT,
                    buildJsonObject {
                        put("text", JsonPrimitive(child.text))
                        put("maxResults", JsonPrimitive(1))
                    },
                ),
            )
        assertEquals("2", output.getValue("matchCount").jsonPrimitive.content)
        assertEquals("true", output.getValue("ambiguous").jsonPrimitive.content)
        assertEquals("true", output.getValue("queryTruncated").jsonPrimitive.content)
        assertEquals("false", output.getValue("captureTruncated").jsonPrimitive.content)
        assertEquals("true", output.getValue("truncated").jsonPrimitive.content)
        assertEquals(1, output.getValue("nodes").jsonArray.size)
        assertFalse(output.containsKey("suggestedClickToken"))
        assertWaitSchema(output)
        val full = completed(execute(AutomationTools.WAIT, args("text" to requireNotNull(child.text))))
        assertEquals("false", full.getValue("truncated").jsonPrimitive.content)
        assertEquals("2", full.getValue("matchCount").jsonPrimitive.content)
        assertFalse(full.containsKey("suggestedClickToken"))
        assertWaitSchema(full)
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test
    fun completeWaitCanSuggestSharedClickableAncestor() {
        val result = clickableAncestorSnapshot()
        val snapshot = requireNotNull(result.snapshot)
        val child = snapshot.nodes.first { it.token == CHILD_TOKEN }
        port.snapshotResult = result.copy(snapshot = snapshot.copy(nodes = snapshot.nodes + child.copy(token = TOKEN)))
        val output = completed(execute(AutomationTools.WAIT, args("text" to requireNotNull(child.text))))
        assertEquals("2", output.getValue("matchCount").jsonPrimitive.content)
        assertEquals("true", output.getValue("ambiguous").jsonPrimitive.content)
        assertEquals("false", output.getValue("truncated").jsonPrimitive.content)
        assertEquals(PARENT_TOKEN, output.getValue("suggestedClickToken").jsonPrimitive.content)
        assertWaitSchema(output)
    }

    @Test
    fun stableWaitReportsDefaultTwentyOfSixtyTwoWithoutIncompleteCapture() {
        val result = successfulSnapshot()
        val snapshot = requireNotNull(result.snapshot)
        port.snapshotResult =
            result.copy(
                snapshot =
                    snapshot.copy(
                        nodes = (1..62).map { snapshot.nodes.single().copy(token = "%032x".format(it)) },
                    ),
            )
        val output =
            completed(
                execute(
                    AutomationTools.WAIT,
                    buildJsonObject {
                        put("condition", JsonPrimitive("stable"))
                        put("stableMillis", JsonPrimitive(50))
                    },
                ),
            )
        assertEquals("STABLE", output.getValue("waitStatus").jsonPrimitive.content)
        assertEquals("62", output.getValue("capturedNodeCount").jsonPrimitive.content)
        assertEquals("62", output.getValue("matchCount").jsonPrimitive.content)
        assertEquals("20", output.getValue("returnedNodeCount").jsonPrimitive.content)
        assertEquals("20", output.getValue("maxResults").jsonPrimitive.content)
        assertEquals(20, output.getValue("nodes").jsonArray.size)
        assertEquals("false", output.getValue("captureTruncated").jsonPrimitive.content)
        assertEquals("true", output.getValue("queryTruncated").jsonPrimitive.content)
        assertEquals("true", output.getValue("truncated").jsonPrimitive.content)
        assertWaitSchema(output)
    }

    @Test
    fun waitQueryScopeIsIndependentOfCapturedTreeSizeAndCaptureCompleteness() {
        val result = successfulSnapshot()
        val snapshot = requireNotNull(result.snapshot)
        val target = snapshot.nodes.single()
        for (captureTruncated in listOf(false, true)) {
            port.snapshotResult =
                result.copy(
                    snapshot =
                        snapshot.copy(
                            nodes =
                                listOf(target) +
                                    (1..61).map {
                                        target.copy(token = "%032x".format(it), text = "Other")
                                    },
                            truncated = captureTruncated,
                        ),
                )
            val output = completed(execute(AutomationTools.WAIT, args("text" to "Continue")))
            assertEquals("FOUND", output.getValue("waitStatus").jsonPrimitive.content)
            assertEquals("1", output.getValue("matchCount").jsonPrimitive.content)
            assertEquals("false", output.getValue("queryTruncated").jsonPrimitive.content)
            assertEquals(captureTruncated.toString(), output.getValue("captureTruncated").jsonPrimitive.content)
            assertEquals(captureTruncated.toString(), output.getValue("truncated").jsonPrimitive.content)
            assertEquals(!captureTruncated, output.containsKey("suggestedClickToken"))
            assertWaitSchema(output)
        }
    }

    @Test
    fun absentWaitReportsZeroMatchesWithoutSuggestingAnAction() {
        port.snapshotResult = successfulSnapshot()
        val output = completed(execute(AutomationTools.WAIT, args("text" to "Missing", "condition" to "absent")))
        assertEquals("ABSENT", output.getValue("waitStatus").jsonPrimitive.content)
        assertEquals("0", output.getValue("matchCount").jsonPrimitive.content)
        assertEquals("0", output.getValue("returnedNodeCount").jsonPrimitive.content)
        assertEquals("false", output.getValue("truncated").jsonPrimitive.content)
        assertFalse(output.containsKey("suggestedClickToken"))
        assertWaitSchema(output)
    }

    private fun assertWaitSchema(output: JsonObject) {
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                tools.descriptors().single { it.name.value == AutomationTools.WAIT }.outputSchema,
                output,
            ),
        )
    }

    @Test
    fun offscreenFindSuggestsScrollWithoutClickToken() {
        val result = successfulSnapshot()
        val snapshot = requireNotNull(result.snapshot)
        val node = snapshot.nodes.single().copy(bounds = AutomationNodeBounds(0, 120, 100, 100))
        val parent = node.copy(token = "parent", bounds = AutomationNodeBounds(0, 0, 100, 100))
        val child = node.copy(parentToken = parent.token)
        assertEquals("", automationClickTargetToken(child, mapOf(parent.token to parent, child.token to child)))
        port.snapshotResult = result.copy(snapshot = snapshot.copy(nodes = listOf(node)))
        val found = completed(execute(AutomationTools.FIND, args("text" to "Continue")))
        val descriptor = tools.descriptors().single { it.name.value == AutomationTools.FIND }
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, found))
        assertFalse(found.containsKey("suggestedClickToken"))
        assertFalse(
            found
                .getValue("actionHint")
                .jsonPrimitive.content
                .contains("not a ui.find"),
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                descriptor.inputSchema,
                buildJsonObject { put("scrollable", JsonPrimitive(true)) },
            ),
        )
        assertTrue(
            found
                .getValue("actionHint")
                .jsonPrimitive.content
                .contains("ui.snapshot"),
        )
        assertTrue(
            found
                .getValue("actionHint")
                .jsonPrimitive.content
                .contains("scrollable=true"),
        )
        assertTrue(port.nodeRequests.isEmpty())
        assertTrue(
            found
                .getValue("actionHint")
                .jsonPrimitive.content
                .contains("ui.scroll"),
        )
        assertEquals(
            "true",
            found
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("offscreen")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun actionableFindAndWaitResultsFitTheirDeclaredOutputSchema() {
        port.snapshotResult = successfulSnapshot()
        for (name in listOf(AutomationTools.FIND, AutomationTools.WAIT)) {
            val output = completed(execute(name, args("text" to "Continue")))
            assertEquals(TOKEN, output.getValue("suggestedClickToken").jsonPrimitive.content)
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(tools.descriptors().single { it.name.value == name }.outputSchema, output),
            )
            assertTrue(
                output
                    .getValue("actionHint")
                    .jsonPrimitive.content
                    .contains("Never reuse the earlier token"),
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
        assertEquals(1, descriptors.getValue(AutomationTools.FIND).version.value)
        assertEquals(1, descriptors.getValue(AutomationTools.WAIT).version.value)
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
        assertTrue(descriptor.description.contains("manual confirmation"))
        assertEquals(1, descriptor.version.value)
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

    @Test fun clickMatchCancellationAfterObservationNeverDispatchesOrReplays() {
        port.snapshotResult = successfulSnapshot()
        val result =
            execute(
                AutomationTools.CLICK_MATCH,
                args("text" to "Continue"),
                object : CancelSignal {
                    override fun isCancelled() = port.snapshots > 0
                },
            )
        assertTrue(result is ToolExecutorResult.CancelledWithEffectTruth)
        assertEquals(1, port.snapshots)
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test fun changingTargetIsNotAutomaticallyClickedAgain() {
        port.snapshotResult = successfulSnapshot()
        port.actionResult = AutomationActionResult(AutomationActionStatus.TARGET_CHANGED)
        val result = execute(AutomationTools.CLICK_MATCH, args("text" to "Continue")) as ToolExecutorResult.Failed
        assertTrue(result.detail.startsWith("TARGET_CHANGED:"))
        assertTrue(result.sideEffectFree)
        assertEquals(1, port.snapshots)
        assertEquals(1, port.nodeRequests.size)
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
        assertTrue(ambiguous.detail.startsWith("TARGET_AMBIGUOUS:"))
        assertTrue(ambiguous.detail.contains("ui.find"))
        assertTrue(ambiguous.sideEffectFree)
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test
    fun clickMatchRefusesMissingAndNonClickableTargetsWithoutSideEffects() {
        port.snapshotResult = successfulSnapshot()
        val missing = execute(AutomationTools.CLICK_MATCH, args("text" to "Missing")) as ToolExecutorResult.Failed
        assertTrue(missing.detail.startsWith("TARGET_NOT_FOUND:"))
        assertTrue(missing.detail.contains("timeoutMillis"))
        assertTrue(missing.sideEffectFree)

        port.snapshotResult = nonClickableSnapshot()
        val inert = execute(AutomationTools.CLICK_MATCH, args("text" to "Label")) as ToolExecutorResult.Failed
        assertTrue(inert.detail.startsWith("TARGET_NOT_CLICKABLE:"))
        assertTrue(inert.detail.contains("clickTargetToken"))
        assertTrue(inert.sideEffectFree)
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test
    fun clickMatchExplainsOffscreenRecoveryWithoutDispatchingAnAction() {
        val result = successfulSnapshot()
        val snapshot = requireNotNull(result.snapshot)
        val node = snapshot.nodes.single().copy(bounds = AutomationNodeBounds(0, 120, 100, 100))
        port.snapshotResult = result.copy(snapshot = snapshot.copy(nodes = listOf(node)))
        val refused = execute(AutomationTools.CLICK_MATCH, args("text" to "Continue")) as ToolExecutorResult.Failed
        assertTrue(refused.detail.startsWith("TARGET_OFFSCREEN:"))
        assertTrue(refused.detail.contains("ui.scroll"))
        assertTrue(refused.sideEffectFree)
        assertFalse(refused.requiresReview)
        assertTrue(port.nodeRequests.isEmpty())
    }

    @Test
    fun actionsForwardTokensAndStableRefusalsNeverBecomeSuccess() {
        val output = completed(execute(AutomationTools.CLICK, args("token" to TOKEN)))
        assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
        assertEquals("UNVERIFIED", output.getValue("effectStatus").jsonPrimitive.content)
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
        assertTrue(failed.detail.startsWith("STALE_TOKEN:"))
        assertTrue(failed.detail.contains("ui.click_match"))
        assertTrue(failed.detail.contains("ui.find") || failed.detail.contains("ui.snapshot"))
        assertEquals("Refusal must not trigger an automatic action replay", 2, port.nodeRequests.size)
        assertEquals("Refusal must leave the recovery decision to the caller", 0, port.snapshots)
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
        assertFalse(port.nodeRequests.last().submit)
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
        assertEquals(1, descriptor.version.value)
        val schema = descriptor.inputSchema
        listOf("forward", "backward", "up", "down", "left", "right").forEach {
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
        listOf("UP", "next", "diagonal").forEach {
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
            kotlinx.serialization.json.JsonArray(
                listOf("forward", "backward", "up", "down", "left", "right")
                    .map(::JsonPrimitive),
            ),
            direction["enum"],
        )
        execute(AutomationTools.SCROLL, args("token" to TOKEN, "direction" to "backward"))
        assertEquals(AutomationNodeAction.SCROLL_BACKWARD, port.nodeRequests.last().action)
        execute(AutomationTools.SCROLL, args("token" to TOKEN, "direction" to "down"))
        assertEquals(AutomationNodeAction.SCROLL_DOWN, port.nodeRequests.last().action)
        execute(AutomationTools.SCROLL, args("token" to TOKEN, "direction" to "left"))
        assertEquals(AutomationNodeAction.SCROLL_LEFT, port.nodeRequests.last().action)
    }

    private fun execute(
        name: String,
        args: JsonObject,
        cancel: CancelSignal = NoCancellation,
    ) = tools.executor(name).execute(
        ExecutableToolCall(
            "call",
            name,
            "1",
            args,
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(15),
            cancel,
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
