package com.helix.tools.browser

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HXA-062 (verification matrix row `:tools:browser:test`): the browser.* tools' fail-closed
 * outcome → result mapping. A [FakeBridge] returns canned [BrowserToolBridge] outcomes; each
 * tool's executor must never claim success on a refusal, a stale token, an unknown tab or a
 * timeout. The mapping IS the tool layer — the WebView / token / policy work lives in the port
 * impl (:feature:browser), so this exercises the model-facing contract in isolation.
 */
class BrowserToolsMappingTest {
    @Test fun publishedActionsDeclareTheirObservationTools() {
        val origin =
            com.helix.tools.framework.ToolOrigin
                .PluginOrigin("browser", "0.1.0", "browser")
        val descriptors = BrowserTools.bindings(bridge, origin).map { it.descriptor }
        val click = descriptors.single { it.name.value == "browser.click" }
        assertEquals(setOf("browser.snapshot", "browser.find"), click.discoveryCompanions.map { it.value }.toSet())
        assertTrue(descriptors.all { it.origin == origin })
    }

    @Test
    fun longSnapshotMetadataFitsItsSchemaWithoutChangingPageIdentity() {
        val original =
            bridge.snapshotResult.copy(
                url = "a".repeat(MAX_URL - 1) + "😀tail",
                title = "a".repeat(MAX_NODE_TEXT - 1) + "😀tail",
                text = "a".repeat(11999) + "😀tail",
            )
        bridge.snapshotResult = original
        val output =
            json(
                run(
                    BrowserSnapshotTool.NAME,
                    BrowserSnapshotTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("a".repeat(MAX_URL - 1), output.getValue("url").jsonPrimitive.content)
        assertEquals("a".repeat(MAX_NODE_TEXT - 1), output.getValue("title").jsonPrimitive.content)
        assertEquals("true", output.getValue("urlTruncated").jsonPrimitive.content)
        assertEquals("true", output.getValue("titleTruncated").jsonPrimitive.content)
        assertEquals("a".repeat(11999), output.getValue("text").jsonPrimitive.content)
        assertEquals("true", output.getValue("textTruncated").jsonPrimitive.content)
        assertTrue(output.keys.indexOf("text") < output.keys.indexOf("nodes"))
        assertEquals(original, bridge.snapshotResult)
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                BrowserSnapshotTool
                    .descriptor(
                        com.helix.tools.framework.ToolOrigin
                            .PluginOrigin("browser", "0.1.0", "browser"),
                    ).outputSchema,
                output,
            ),
        )
        bridge.snapshotResult = original.copy(url = "a".repeat(MAX_URL), title = "a".repeat(MAX_NODE_TEXT))
        val exact =
            json(
                run(
                    BrowserSnapshotTool.NAME,
                    BrowserSnapshotTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("false", exact.getValue("urlTruncated").jsonPrimitive.content)
        assertEquals("false", exact.getValue("titleTruncated").jsonPrimitive.content)
    }

    @Test
    fun textSnapshotOmitsNodesButPreservesObservationAndDefaultFullView() {
        val nodes =
            List(100) { index ->
                BrowserNodeView(index, "link", "Page $index", "", "https://example.com/$index", "", "token-$index")
            }
        val original =
            bridge.snapshotResult.copy(
                nodes = nodes,
                nodeCount = nodes.size,
                truncated = true,
                text = "Ordinary page paragraph.",
                textTruncated = false,
                observedAtMillis = 1234,
                navigationGeneration = 7,
            )
        bridge.snapshotResult = original
        val descriptor =
            BrowserSnapshotTool.descriptor(
                com.helix.tools.framework.ToolOrigin
                    .PluginOrigin("browser", "0.1.0", "browser"),
            )

        fun snapshot(view: String?): JsonObject {
            val args =
                buildJsonObject {
                    put("tabId", "t1")
                    if (view != null) put("view", view)
                }
            assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, args))
            val output = json(run(BrowserSnapshotTool.NAME, BrowserSnapshotTool.executor(bridge), args))
            assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, output))
            return output
        }
        val full = snapshot(null)
        assertEquals(full, snapshot("full"))
        assertEquals("full", full.getValue("view").jsonPrimitive.content)
        assertEquals(
            "token-0",
            full
                .getValue("nodes")
                .let { it as kotlinx.serialization.json.JsonArray }[0]
                .jsonObject
                .getValue("token")
                .jsonPrimitive.content,
        )
        val text = snapshot("text")
        assertEquals("text", text.getValue("view").jsonPrimitive.content)
        assertFalse(text.containsKey("nodes"))
        assertEquals("100", text.getValue("nodeCount").jsonPrimitive.content)
        assertEquals(
            full.filterKeys { it != "nodes" && it != "view" },
            text.filterKeys {
                it != "view" && it != "links" &&
                    it != "linksTruncated"
            },
        )
        assertTrue(text.toString().length < full.toString().length / 2)
        assertEquals(original, bridge.snapshotResult)
        assertEquals(3, bridge.snapshotCalls)
    }

    @Test
    fun textSnapshotKeepsIndependentTextAndSourceTruncationFacts() {
        bridge.snapshotResult =
            bridge.snapshotResult.copy(
                url = "a".repeat(MAX_URL - 1) + "😀tail",
                title = "a".repeat(MAX_NODE_TEXT - 1) + "😀tail",
                text = "a".repeat(11999) + "😀tail",
                truncated = false,
            )
        val output =
            json(
                run(
                    BrowserSnapshotTool.NAME,
                    BrowserSnapshotTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", "t1")
                        put("view", "text")
                    },
                ),
            )
        assertEquals("a".repeat(MAX_URL - 1), output.getValue("url").jsonPrimitive.content)
        assertEquals("a".repeat(MAX_NODE_TEXT - 1), output.getValue("title").jsonPrimitive.content)
        assertEquals("a".repeat(11999), output.getValue("text").jsonPrimitive.content)
        for (key in listOf("urlTruncated", "titleTruncated", "textTruncated")) {
            assertEquals("true", output.getValue(key).jsonPrimitive.content)
        }
        assertEquals("false", output.getValue("truncated").jsonPrimitive.content)
        assertFalse(output.containsKey("nodes"))
    }

    @Test
    fun textSnapshotPreservesSourceTextTruncationWithoutTreeTruncation() {
        bridge.snapshotResult =
            bridge.snapshotResult.copy(
                text = "Partial paragraph within the output length limit.",
                textTruncated = true,
                truncated = false,
            )
        val output =
            json(
                run(
                    BrowserSnapshotTool.NAME,
                    BrowserSnapshotTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", "t1")
                        put("view", "text")
                    },
                ),
            )
        assertEquals(bridge.snapshotResult.text, output.getValue("text").jsonPrimitive.content)
        assertEquals("true", output.getValue("textTruncated").jsonPrimitive.content)
        assertEquals("false", output.getValue("truncated").jsonPrimitive.content)
        assertEquals("text", output.getValue("view").jsonPrimitive.content)
        assertFalse(output.containsKey("nodes"))
    }

    @Test
    fun invalidSnapshotViewsFailBeforeObservation() {
        for (view in listOf(JsonPrimitive("unknown"), JsonPrimitive(1), JsonNull)) {
            val result =
                run(
                    BrowserSnapshotTool.NAME,
                    BrowserSnapshotTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", "t1")
                        put("view", view)
                    },
                )
            assertTrue(failed(result).detail.contains("'view'"))
        }
        assertEquals(0, bridge.snapshotCalls)
    }

    @Test
    fun textSnapshotPreservesFailureAndCancellation() {
        val args =
            buildJsonObject {
                put("tabId", "t1")
                put("view", "text")
            }
        val executor = BrowserSnapshotTool.executor(bridge)
        val cancelled =
            call(BrowserSnapshotTool.NAME, args).copy(
                cancel =
                    object : CancelSignal {
                        override fun isCancelled(): Boolean = true
                    },
            )
        assertEquals(ToolExecutorResult.Cancelled, executor.execute(cancelled))
        assertEquals(0, bridge.snapshotCalls)
        bridge.snapshotResult = bridge.snapshotResult.copy(ok = false, message = "no page")
        assertEquals("no page", failed(executor.execute(call(BrowserSnapshotTool.NAME, args))).detail)
        assertEquals(1, bridge.snapshotCalls)
    }

    @Test
    fun searchEncodesTheQueryAndRetainsTheOriginalSession() {
        bridge.echoOpenUrl = true
        val args =
            buildJsonObject {
                put("query", "中文 &x=1#frag +")
                put("engine", "bing")
            }
        val output =
            json(
                BrowserSearchTool.executor(bridge).execute(
                    call(BrowserSearchTool.NAME, args).copy(sessionId = "search-owner"),
                ),
            )
        assertEquals("search-owner", bridge.openSession)
        assertEquals(
            "https://www.bing.com/search?q=%E4%B8%AD%E6%96%87+%26x%3D1%23frag+%2B",
            bridge.openUrl,
        )
        assertEquals("started", output.getValue("status").jsonPrimitive.content)
        assertEquals("bing", output.getValue("engine").jsonPrimitive.content)
        assertFalse(output.containsKey("results"))
        val origin =
            com.helix.tools.framework.ToolOrigin
                .PluginOrigin("browser", "0.1.0", "browser")
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                BrowserSearchTool.descriptor(origin).outputSchema,
                output,
            ),
        )
    }

    @Test
    fun searchValidatesEngineQueryAndCancellationBeforeOpening() {
        val executor = BrowserSearchTool.executor(bridge)
        val valid =
            buildJsonObject {
                put("query", "query")
                put("engine", "google")
            }
        for (args in listOf(
            buildJsonObject {
                put("query", " ")
                put("engine", "bing")
            },
            buildJsonObject {
                put("query", "q")
                put("engine", "deepseek")
            },
            buildJsonObject {
                put("query", "q")
                put("engine", "https://evil.example/")
            },
            buildJsonObject {
                put("query", "x".repeat(201))
                put("engine", "bing")
            },
            buildJsonObject {
                put("query", "q")
                put("engine", "bing")
                put("sessionId", "other")
            },
        )) {
            assertTrue(executor.execute(call(BrowserSearchTool.NAME, args)) is ToolExecutorResult.Failed)
        }
        assertEquals(
            ToolExecutorResult.TimedOut,
            executor.execute(
                call(BrowserSearchTool.NAME, valid).copy(deadline = Instant.EPOCH),
            ),
        )
        assertEquals(
            ToolExecutorResult.Cancelled,
            executor.execute(
                call(BrowserSearchTool.NAME, valid).copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = true
                        },
                ),
            ),
        )
        assertEquals(null, bridge.openUrl)
    }

    @Test
    fun searchUsesEveryExplicitEngineAndPropagatesHostFailure() {
        bridge.echoOpenUrl = true
        val executor = BrowserSearchTool.executor(bridge)
        for ((engine, prefix) in mapOf(
            "google" to "https://www.google.com/search?q=",
            "bing" to "https://www.bing.com/search?q=",
            "duckduckgo" to "https://duckduckgo.com/?q=",
        )) {
            json(
                executor.execute(
                    call(
                        BrowserSearchTool.NAME,
                        buildJsonObject {
                            put("query", "中".repeat(200))
                            put("engine", engine)
                        },
                    ),
                ),
            )
            assertTrue(bridge.openUrl!!.startsWith(prefix))
            assertTrue(bridge.openUrl!!.length <= MAX_URL)
        }
        bridge.openResult = OpenOutcome("", "", "", "browser-host-unavailable")
        assertEquals(
            "browser-host-unavailable",
            failed(
                executor.execute(
                    call(
                        BrowserSearchTool.NAME,
                        buildJsonObject {
                            put("query", "q")
                            put("engine", "bing")
                        },
                    ),
                ),
            ).detail,
        )
    }

    @Test
    fun searchDoesNotClaimResultsForDeniedOrUncertainNavigation() {
        val args =
            buildJsonObject {
                put("query", "q")
                put("engine", "bing")
            }
        bridge.openResult = OpenOutcome("t", "about:blank", "about:blank")
        assertTrue(
            BrowserSearchTool.executor(bridge).execute(call(BrowserSearchTool.NAME, args))
                is ToolExecutorResult.Failed,
        )
        bridge.openResult = OpenOutcome("", "", "", "browser-open-outcome-unknown")
        assertTrue(
            failed(BrowserSearchTool.executor(bridge).execute(call(BrowserSearchTool.NAME, args))).requiresReview,
        )
    }

    private val bridge = FakeBridge()

    @Test fun escapedTabMetadataFitsTheDeclaredOutputBudget() {
        bridge.tabsResult =
            BrowserTabsOutcome(
                (0..31).map {
                    BrowserTabView(
                        "tab-$it",
                        "\u0000".repeat(BrowserTabView.MAX_URL_CHARS),
                        "\u0000".repeat(BrowserTabView.MAX_TITLE_CHARS),
                        false,
                        "READY",
                        true,
                        1,
                        true,
                        true,
                    )
                },
                true,
                false,
            )
        val result =
            BrowserTabsTool.executor(bridge).execute(call(BrowserTabsTool.NAME, buildJsonObject {}))
                as ToolExecutorResult.Completed
        val origin =
            com.helix.tools.framework.ToolOrigin
                .PluginOrigin("browser", "0.1.0", "browser")
        assertTrue(
            result.output
                .toString()
                .toByteArray()
                .size <= BrowserTabsTool.descriptor(origin).maxOutputBytes,
        )
    }

    @Test
    fun scopedDiscoveryUsesOnlyTrustedCallIdentityAndWaitDoesNotInventUniqueToken() {
        val contextCall =
            call(BrowserTabsTool.NAME, buildJsonObject {}).copy(sessionId = "s", authorizationScopeRef = "scope")
        json(BrowserTabsTool.executor(bridge).execute(contextCall))
        assertEquals(BrowserObservationContext("s", "scope"), bridge.tabsContext)
        val args =
            buildJsonObject {
                put("tabId", JsonPrimitive("t1"))
                put("query", JsonPrimitive("Same"))
                put("condition", JsonPrimitive("PRESENT"))
                put("timeoutMillis", JsonPrimitive(500))
            }
        bridge.waitResult =
            BrowserWaitOutcome(
                BrowserWaitStatus.SATISFIED,
                FindOutcome(
                    true,
                    "t1",
                    "Same",
                    2,
                    listOf(BrowserFindMatch(0, "button", "Same", "token")),
                    "",
                    true,
                    false,
                ),
            )
        val output = json(BrowserWaitTool.executor(bridge).execute(contextCall.copy(args = args)))
        assertEquals("true", output.getValue("ambiguous").jsonPrimitive.content)
        assertTrue(!output.containsKey("token"))
        assertEquals(contextCall.deadline, bridge.waitRequest!!.deadline)
        assertEquals(BrowserObservationContext("s", "scope"), bridge.waitRequest!!.context)
        bridge.waitResult = BrowserWaitOutcome(BrowserWaitStatus.CANCELLED)
        assertEquals(
            ToolExecutorResult.Cancelled,
            BrowserWaitTool.executor(bridge).execute(contextCall.copy(args = args)),
        )
        assertTrue(
            BrowserWaitTool.executor(bridge).execute(
                contextCall.copy(
                    args =
                        JsonObject(
                            args +
                                ("timeoutMillis" to JsonPrimitive(10001)),
                        ),
                ),
            ) is ToolExecutorResult.Failed,
        )
    }

    @Test
    fun uploadRequiresSourceIdentityAndNeverReportsUnknownAsAttached() {
        val args =
            buildJsonObject {
                put("tabId", JsonPrimitive("t1"))
                put("token", JsonPrimitive("token"))
                put("pageUrl", JsonPrimitive("https://example.com/"))
                put("path", JsonPrimitive("scope:app:fixture.txt"))
                put("sha256", JsonPrimitive("a".repeat(64)))
            }
        bridge.uploadResult = BrowserUploadOutcome(ActionOutcome(ActionStatus.PERFORMED, 1, "input", "field", ""), 3)
        assertEquals(
            "attached",
            json(
                run(BrowserUploadTool.NAME, BrowserUploadTool.executor(bridge), args),
            )["status"]!!.jsonPrimitive.content,
        )
        bridge.uploadResult = BrowserUploadOutcome(ActionOutcome(ActionStatus.UNKNOWN, 1, "", "", "lost-page"))
        val uncertain = failed(run(BrowserUploadTool.NAME, BrowserUploadTool.executor(bridge), args))
        assertTrue(uncertain.requiresReview)
        assertFalse(uncertain.sideEffectFree)
        assertTrue(
            failed(
                run(BrowserUploadTool.NAME, BrowserUploadTool.executor(bridge), JsonObject(args - "sha256")),
            ).sideEffectFree,
        )
    }

    @Test
    fun browserContributionHasOnePluginOwnerForEveryContract() {
        val origin =
            com.helix.tools.framework.ToolOrigin
                .PluginOrigin("browser", "0.1.0", "browser")
        val bindings = BrowserTools.bindings(bridge, origin)
        assertEquals(20, bindings.size)
        assertEquals(20, bindings.map { it.descriptor.name }.distinct().size)
        assertTrue(bindings.all { it.descriptor.origin == origin })
        assertTrue(
            bindings.all {
                it.descriptor.name.value
                    .startsWith("browser.")
            },
        )
    }

    @Test
    fun nodeActionsRemainNonIdempotentAndUnknownRequiresReview() {
        val origin =
            com.helix.tools.framework.ToolOrigin
                .PluginOrigin("browser", "1", "browser")
        for (action in BrowserNodeAction.entries) {
            assertEquals(
                com.helix.tools.framework.Idempotency.NON_IDEMPOTENT,
                BrowserNodeActionTool.descriptor(origin, action).idempotency,
            )
        }
        val result =
            actionResult(ActionOutcome(ActionStatus.UNKNOWN, 0, "button", "button", ""))
                as ToolExecutorResult.Failed
        assertTrue(result.requiresReview)
        assertFalse(result.sideEffectFree)
    }

    @Test
    fun invalidSyntheticKeyNeverReachesBridge() {
        val args =
            buildJsonObject {
                put("tabId", "tab")
                put("token", "token")
                put("key", "Unrecognized")
            }
        assertTrue(
            BrowserNodeActionTool
                .executor(bridge, BrowserNodeAction.PRESS)
                .execute(call("browser.press", args)) is ToolExecutorResult.Failed,
        )
    }

    private val noCancel =
        object : CancelSignal {
            override fun isCancelled(): Boolean = false
        }

    private fun call(
        name: String,
        args: JsonObject,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = name,
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = noCancel,
        )

    private fun run(
        name: String,
        executor: ToolExecutor,
        args: JsonObject,
    ): ToolExecutorResult = executor.execute(call(name, args))

    private fun json(result: ToolExecutorResult): JsonObject {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return c.output.jsonObject
    }

    private fun failed(result: ToolExecutorResult): ToolExecutorResult.Failed {
        val f = result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")
        return f
    }

    // ── open ───────────────────────────────────────────────────────────────────────────

    @Test
    fun openReportsTheNewTab() {
        bridge.openResult = OpenOutcome("t7", "https://example.com/", "example.com")
        val out =
            json(
                run(
                    BrowserOpenTool.NAME,
                    BrowserOpenTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://example.com/")) },
                ),
            )
        assertEquals("t7", out.getValue("tabId").jsonPrimitive.content)
        assertEquals("https://example.com/", out.getValue("url").jsonPrimitive.content)
        assertEquals("example.com", out.getValue("origin").jsonPrimitive.content)
    }

    @Test
    fun missingBrowserHostIsAFailureInsteadOfASuccessfulBlankTab() {
        bridge.openResult = OpenOutcome("", "", "", "browser-host-unavailable")
        val result =
            run(
                BrowserOpenTool.NAME,
                BrowserOpenTool.executor(bridge),
                buildJsonObject { put("url", JsonPrimitive("https://example.com/")) },
            )
        assertTrue(result is ToolExecutorResult.Failed)
    }

    // ── navigate: the policy / unknown-tab / timeout outcomes ──────────────────────────

    @Test
    fun navigateStartedEmitsTheCommittedUrlWithEmptyReason() {
        bridge.navResult = NavigateOutcome(NavStatus.STARTED, "https://a.example/", "a.example", "")
        val out =
            json(
                run(
                    BrowserNavigateTool.NAME,
                    BrowserNavigateTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("url", JsonPrimitive("https://a.example/"))
                    },
                ),
            )
        assertEquals("started", out.getValue("status").jsonPrimitive.content)
        assertEquals("https://a.example/", out.getValue("url").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun navigateDeniedKeepsTheReasonAndEmitsAnEmptyUrl() {
        bridge.navResult = NavigateOutcome(NavStatus.DENIED, "", "", "scheme not allowed")
        val out =
            json(
                run(
                    BrowserNavigateTool.NAME,
                    BrowserNavigateTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("url", JsonPrimitive("file:///etc/passwd"))
                    },
                ),
            )
        assertEquals("denied", out.getValue("status").jsonPrimitive.content)
        assertEquals("", out.getValue("url").jsonPrimitive.content)
        assertEquals("scheme not allowed", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun navigateUnknownTabIsAFailClosedFailure() {
        bridge.navResult = NavigateOutcome(NavStatus.NO_TAB, "", "", "")
        val f =
            failed(
                run(
                    BrowserNavigateTool.NAME,
                    BrowserNavigateTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("nope"))
                        put("url", JsonPrimitive("https://a.example/"))
                    },
                ),
            )
        assertTrue(f.detail.contains("unknown tab"))
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun navigateTimeoutIsATimedOutResult() {
        bridge.navResult = NavigateOutcome(NavStatus.TIMED_OUT, "", "", "")
        val r =
            run(
                BrowserNavigateTool.NAME,
                BrowserNavigateTool.executor(bridge),
                buildJsonObject {
                    put("tabId", JsonPrimitive("t1"))
                    put("url", JsonPrimitive("https://a.example/"))
                },
            )
        assertEquals(ToolExecutorResult.TimedOut, r)
    }

    // ── back / forward / reload history outcomes ───────────────────────────────────────

    @Test
    fun backMovedEmitsTheResultingFlags() {
        bridge.backResult = HistoryOutcome(HistStatus.MOVED, "https://a.example/", "a.example", true, false, "")
        val out =
            json(
                run(
                    BrowserBackTool.NAME,
                    BrowserBackTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("moved", out.getValue("status").jsonPrimitive.content)
        assertEquals(
            true,
            out
                .getValue("canGoBack")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            false,
            out
                .getValue("canGoForward")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test
    fun backNoChangeKeepsTheReason() {
        bridge.backResult =
            HistoryOutcome(HistStatus.NO_CHANGE, "https://a.example/", "a.example", false, false, "no history")
        val out =
            json(
                run(
                    BrowserBackTool.NAME,
                    BrowserBackTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("no-change", out.getValue("status").jsonPrimitive.content)
        assertEquals("no history", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun reloadUnknownTabIsAFailClosedFailure() {
        bridge.reloadResult = ReloadOutcome(ReloadStatus.NO_TAB, "", "", "")
        val f =
            failed(
                run(
                    BrowserReloadTool.NAME,
                    BrowserReloadTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("nope")) },
                ),
            )
        assertTrue(f.detail.contains("unknown tab"))
        assertTrue(f.sideEffectFree)
    }

    // ── click / type: the token + sensitive-field outcomes ─────────────────────────────

    @Test
    fun clickPerformedEmitsPerformedWithAnEmptyReason() {
        bridge.clickResult = ActionOutcome(ActionStatus.PERFORMED, 3, "button", "button", "")
        val out =
            json(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("performed", out.getValue("status").jsonPrimitive.content)
        assertEquals("3", out.getValue("nodeIndex").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clickRefusedEmitsTheRefusalCategoryNeverPerformed() {
        bridge.clickResult = ActionOutcome(ActionStatus.REFUSED, 5, "input", "field", "payment")
        val out =
            json(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("refused", out.getValue("status").jsonPrimitive.content)
        assertEquals("payment", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clickStaleTokenIsACompletedStaleTokenNotAFailure() {
        bridge.clickResult = ActionOutcome(ActionStatus.STALE_TOKEN, 7, "", "", "stale-fingerprint")
        val out =
            json(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("stale-token", out.getValue("status").jsonPrimitive.content)
        assertEquals("stale-fingerprint", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun clickUnknownTabIsAFailClosedFailure() {
        bridge.clickResult = ActionOutcome(ActionStatus.NO_TAB, -1, "", "", "")
        val f =
            failed(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("nope"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertTrue(f.detail.contains("unknown tab"))
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun clickErrorIsATerminalFailure() {
        bridge.clickResult = ActionOutcome(ActionStatus.ERROR, 1, "input", "field", "bad result")
        val f =
            failed(
                run(
                    BrowserClickTool.NAME,
                    BrowserClickTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("token", JsonPrimitive("tok"))
                    },
                ),
            )
        assertEquals("bad result", f.detail)
    }

    @Test
    fun typeTimeoutIsATimedOutResult() {
        bridge.typeResult = ActionOutcome(ActionStatus.TIMED_OUT, 1, "", "", "")
        val r =
            run(
                BrowserTypeTool.NAME,
                BrowserTypeTool.executor(bridge),
                buildJsonObject {
                    put("tabId", JsonPrimitive("t1"))
                    put("token", JsonPrimitive("tok"))
                    put("text", JsonPrimitive("x"))
                },
            )
        assertEquals(ToolExecutorResult.TimedOut, r)
    }

    // ── snapshot / find ────────────────────────────────────────────────────────────────

    @Test
    fun snapshotFailureIsAFailClosedFailure() {
        bridge.snapshotResult = SnapshotOutcome(false, "t1", "", "", "", 0, "", false, 0, emptyList(), "no page")
        val f =
            failed(
                run(
                    BrowserSnapshotTool.NAME,
                    BrowserSnapshotTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertTrue(f.detail.contains("no page"))
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun findWithoutASnapshotIsAFailClosedFailure() {
        bridge.findResult = FindOutcome(false, "t1", "x", 0, emptyList(), "no snapshot yet")
        val f =
            failed(
                run(
                    BrowserFindTool.NAME,
                    BrowserFindTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("query", JsonPrimitive("x"))
                    },
                ),
            )
        assertTrue(f.detail.contains("no snapshot yet"))
        assertTrue(f.sideEffectFree)
    }

    // ── scroll / screenshot ────────────────────────────────────────────────────────────

    @Test
    fun scrollNoPageIsAFailClosedFailure() {
        bridge.scrollResult = ScrollOutcome(ScrollStatus.NO_PAGE, 0, 100, "")
        val f =
            failed(
                run(
                    BrowserScrollTool.NAME,
                    BrowserScrollTool.executor(bridge),
                    buildJsonObject {
                        put("tabId", JsonPrimitive("t1"))
                        put("dx", JsonPrimitive(0))
                        put("dy", JsonPrimitive(100))
                    },
                ),
            )
        assertEquals("no scrollable page in that tab", f.detail)
        assertTrue(f.sideEffectFree)
    }

    @Test
    fun screenshotSharesTheTypedImagePreparationChannel() {
        bridge.screenshotResult =
            ScreenshotOutcome(ScreenshotStatus.SAVED, "scope:app:output/s.png", 128, "a".repeat(64), "")
        val image =
            com.helix.core.model
                .VisualArtifact("art", "b".repeat(64), "image/png", 128, 16, 16)
        val preparation =
            com.helix.tools.framework.ToolVisualPreparation { _, reference, sha ->
                assertEquals("scope:app:output/s.png", reference)
                assertEquals("a".repeat(64), sha)
                image
            }
        val result =
            run(
                BrowserScreenshotTool.NAME,
                BrowserScreenshotTool.executor(bridge, preparation),
                buildJsonObject { put("tabId", JsonPrimitive("t1")) },
            ) as ToolExecutorResult.Completed
        assertEquals(image, result.visualArtifact)
        assertEquals(
            "true",
            result.output.jsonObject
                .getValue("pixelsAttached")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun screenshotSavedDoesNotClaimPixelsWhenVisionIsUnavailable() {
        bridge.screenshotResult =
            ScreenshotOutcome(ScreenshotStatus.SAVED, "scope:app:output/s.png", 128, "a".repeat(64), "")
        val preparation =
            com.helix.tools.framework.ToolVisualPreparation { _, _, _ ->
                throw com.helix.tools.framework
                    .VisualPreparationException("VISION_UNAVAILABLE")
            }
        val result =
            run(
                BrowserScreenshotTool.NAME,
                BrowserScreenshotTool.executor(bridge, preparation),
                buildJsonObject { put("tabId", JsonPrimitive("t1")) },
            ) as ToolExecutorResult.Completed
        org.junit.Assert.assertNull(result.visualArtifact)
        assertTrue(
            result.output.jsonObject
                .getValue("reason")
                .jsonPrimitive.content
                .contains("pixels not delivered"),
        )
    }

    @Test
    fun screenshotSavedEmitsTheModelSafeReferenceAndAudit() {
        bridge.screenshotResult =
            ScreenshotOutcome(ScreenshotStatus.SAVED, "scope:app:output/browser-1.png", 2048L, "ab12", "")
        val r =
            run(
                BrowserScreenshotTool.NAME,
                BrowserScreenshotTool.executor(bridge),
                buildJsonObject { put("tabId", JsonPrimitive("t1")) },
            )
        val out = json(r)
        assertEquals("saved", out.getValue("status").jsonPrimitive.content)
        assertEquals("scope:app:output/browser-1.png", out.getValue("reference").jsonPrimitive.content)
        assertEquals("2048", out.getValue("sizeBytes").jsonPrimitive.content)
        assertEquals("ab12", out.getValue("sha256").jsonPrimitive.content)
        // The audit detail mirrors the reference; the model output never carries a raw path.
        val c = r as ToolExecutorResult.Completed
        assertEquals(
            "scope:app:output/browser-1.png",
            c.auditDetail
                ?.getValue("reference")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun screenshotNoPageIsAFailClosedFailure() {
        bridge.screenshotResult = ScreenshotOutcome(ScreenshotStatus.NO_PAGE, "", 0L, "", "")
        val f =
            failed(
                run(
                    BrowserScreenshotTool.NAME,
                    BrowserScreenshotTool.executor(bridge),
                    buildJsonObject { put("tabId", JsonPrimitive("t1")) },
                ),
            )
        assertEquals("no captured page in that tab", f.detail)
        assertTrue(f.sideEffectFree)
    }

    // ── download ─────────────────────────────────────────────────────────────────────────

    @Test
    fun downloadSavedEmitsAllFieldsWithAnAuditReference() {
        bridge.downloadResult =
            DownloadOutcome(
                DownloadToolStatus.SAVED,
                "report.bin",
                "https://a.example/files/report.bin",
                "scope:app:output/report.bin",
                4096L,
                "ab12",
                "application/octet-stream",
                "",
            )
        val r =
            run(
                BrowserDownloadTool.NAME,
                BrowserDownloadTool.executor(bridge),
                buildJsonObject {
                    put("url", JsonPrimitive("https://a.example/files/report.bin"))
                    put("suggestedName", JsonPrimitive("hint.bin"))
                },
            )
        val out = json(r)
        assertEquals("saved", out.getValue("status").jsonPrimitive.content)
        assertEquals("report.bin", out.getValue("fileName").jsonPrimitive.content)
        assertEquals("https://a.example/files/report.bin", out.getValue("finalUrl").jsonPrimitive.content)
        assertEquals("scope:app:output/report.bin", out.getValue("reference").jsonPrimitive.content)
        assertEquals("4096", out.getValue("sizeBytes").jsonPrimitive.content)
        assertEquals("ab12", out.getValue("sha256").jsonPrimitive.content)
        assertEquals("application/octet-stream", out.getValue("contentType").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
        val c = r as ToolExecutorResult.Completed
        assertEquals(
            "scope:app:output/report.bin",
            c.auditDetail
                ?.getValue("reference")
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun downloadRefusedEmitsTheReasonWithAnEmptyPayload() {
        bridge.downloadResult =
            DownloadOutcome(
                DownloadToolStatus.REFUSED,
                "",
                "https://a.example/files/x.apk",
                "",
                0L,
                "",
                "",
                "type",
            )
        val out =
            json(
                run(
                    BrowserDownloadTool.NAME,
                    BrowserDownloadTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://a.example/files/x.apk")) },
                ),
            )
        assertEquals("refused", out.getValue("status").jsonPrimitive.content)
        assertEquals("type", out.getValue("reason").jsonPrimitive.content)
        assertEquals("", out.getValue("fileName").jsonPrimitive.content)
        assertEquals("", out.getValue("reference").jsonPrimitive.content)
        assertEquals("0", out.getValue("sizeBytes").jsonPrimitive.content)
    }

    @Test
    fun downloadTimeoutIsATimedOutResult() {
        bridge.downloadResult =
            DownloadOutcome(DownloadToolStatus.TIMED_OUT, "", "", "", 0L, "", "", "timed-out")
        val r =
            run(
                BrowserDownloadTool.NAME,
                BrowserDownloadTool.executor(bridge),
                buildJsonObject { put("url", JsonPrimitive("https://a.example/slow")) },
            )
        assertEquals(ToolExecutorResult.TimedOut, r)
    }

    @Test
    fun downloadErrorIsAFailureCarryingTheReason() {
        bridge.downloadResult = DownloadOutcome(DownloadToolStatus.ERROR, "", "", "", 0L, "", "", "boom")
        val f =
            failed(
                run(
                    BrowserDownloadTool.NAME,
                    BrowserDownloadTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://a.example/x")) },
                ),
            )
        assertEquals("boom", f.detail)
    }

    @Test
    fun downloadErrorWithAnEmptyReasonFallsBackToAStableMessage() {
        bridge.downloadResult = DownloadOutcome(DownloadToolStatus.ERROR, "", "", "", 0L, "", "", "")
        val f =
            failed(
                run(
                    BrowserDownloadTool.NAME,
                    BrowserDownloadTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://a.example/x")) },
                ),
            )
        assertEquals("download failed", f.detail)
    }

    @Test
    fun downloadRequiresAStringUrl() {
        val f =
            failed(
                run(
                    BrowserDownloadTool.NAME,
                    BrowserDownloadTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive(42)) },
                ),
            )
        assertTrue(f.detail.contains("'url' must be a string"))
    }

    @Test
    fun downloadSuggestedNameIsOptional() {
        bridge.downloadResult =
            DownloadOutcome(
                DownloadToolStatus.SAVED,
                "report.bin",
                "https://a.example/files/report.bin",
                "scope:app:output/report.bin",
                12L,
                "ab12",
                "",
                "",
            )
        val out =
            json(
                run(
                    BrowserDownloadTool.NAME,
                    BrowserDownloadTool.executor(bridge),
                    buildJsonObject { put("url", JsonPrimitive("https://a.example/files/report.bin")) },
                ),
            )
        assertEquals("saved", out.getValue("status").jsonPrimitive.content)
        assertEquals("report.bin", out.getValue("fileName").jsonPrimitive.content)
    }

    /** Returns one canned outcome per port method; each test sets the field it exercises. */
    private class FakeBridge : BrowserToolBridge {
        var uploadResult = BrowserUploadOutcome(ActionOutcome(ActionStatus.REFUSED, -1, "", "", "fixture"))

        override fun upload(
            request: BrowserUploadRequest,
            isActive: () -> Boolean,
        ) = uploadResult

        var tabsContext: BrowserObservationContext? = null
        var tabsResult: BrowserTabsOutcome = BrowserTabsOutcome(emptyList(), false, false)
        var waitRequest: BrowserWaitRequest? = null
        var waitResult: BrowserWaitOutcome = BrowserWaitOutcome(BrowserWaitStatus.TIMED_OUT)
        var openResult: OpenOutcome = OpenOutcome("t1", "https://example.com/", "example.com")
        var navResult: NavigateOutcome =
            NavigateOutcome(NavStatus.STARTED, "https://example.com/", "example.com", "")
        var backResult: HistoryOutcome =
            HistoryOutcome(HistStatus.MOVED, "https://example.com/", "example.com", true, false, "")
        var reloadResult: ReloadOutcome =
            ReloadOutcome(ReloadStatus.RELOADED, "https://example.com/", "example.com", "")
        var snapshotResult: SnapshotOutcome =
            SnapshotOutcome(
                true,
                "t1",
                "https://example.com/",
                "Example",
                "example.com",
                1,
                "fp",
                false,
                0,
                emptyList(),
                "",
            )
        var findResult: FindOutcome = FindOutcome(true, "t1", "x", 0, emptyList(), "")
        var clickResult: ActionOutcome = ActionOutcome(ActionStatus.PERFORMED, 0, "button", "button", "")
        var typeResult: ActionOutcome = ActionOutcome(ActionStatus.PERFORMED, 0, "input", "field", "")
        var scrollResult: ScrollOutcome = ScrollOutcome(ScrollStatus.SCROLLED, 0, 100, "")
        var screenshotResult: ScreenshotOutcome =
            ScreenshotOutcome(ScreenshotStatus.SAVED, "scope:app:output/browser-x.png", 1L, "sha", "")
        var downloadResult: DownloadOutcome =
            DownloadOutcome(
                DownloadToolStatus.SAVED,
                "x.bin",
                "https://a.example/file.bin",
                "scope:app:output/x.bin",
                1L,
                "sha",
                "application/octet-stream",
                "",
            )

        var echoOpenUrl = false
        var openUrl: String? = null
        var openSession: String? = null

        override fun open(
            url: String,
            sessionId: String?,
            isActive: () -> Boolean,
        ): OpenOutcome {
            openUrl = url
            openSession = sessionId
            return if (echoOpenUrl) openResult.copy(url = url) else openResult
        }

        override fun tabs(context: BrowserObservationContext): BrowserTabsOutcome {
            tabsContext = context
            return tabsResult
        }

        override fun waitFor(request: BrowserWaitRequest): BrowserWaitOutcome {
            waitRequest = request
            return waitResult
        }

        override fun navigate(
            tabId: String,
            url: String,
        ): NavigateOutcome = navResult

        override fun back(tabId: String): HistoryOutcome = backResult

        override fun forward(tabId: String): HistoryOutcome = backResult

        override fun reload(tabId: String): ReloadOutcome = reloadResult

        var snapshotCalls = 0

        override fun snapshot(tabId: String): SnapshotOutcome {
            snapshotCalls++
            return snapshotResult
        }

        override fun find(
            tabId: String,
            query: String,
        ): FindOutcome = findResult

        override fun click(
            tabId: String,
            token: String,
        ): ActionOutcome = clickResult

        override fun nodeAction(
            tabId: String,
            token: String,
            action: BrowserNodeAction,
            value: String,
            isActive: () -> Boolean,
        ): ActionOutcome = click(tabId, token)

        override fun type(
            tabId: String,
            token: String,
            text: String,
        ): ActionOutcome = typeResult

        override fun scroll(
            tabId: String,
            dx: Int,
            dy: Int,
        ): ScrollOutcome = scrollResult

        override fun screenshot(tabId: String): ScreenshotOutcome = screenshotResult

        override fun download(
            url: String,
            suggestedName: String,
        ): DownloadOutcome = downloadResult
    }
}
