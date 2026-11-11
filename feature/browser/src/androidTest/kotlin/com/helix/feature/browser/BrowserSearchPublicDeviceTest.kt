package com.helix.feature.browser

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.browser.BrowserObservationContext
import com.helix.tools.browser.BrowserSearchTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Explicit public-network smoke, no account, provider key or paid API; run separately from offline boundaries. */
class BrowserSearchPublicDeviceTest {
    @Test
    fun publicBingSearchLoadsObservableResultsInItsSessionTab() {
        BrowserActivityFixture().use { fixture ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val store = WorkspaceArtifactStore(ScopeRootResolver { instrumentation.targetContext.cacheDir.toPath() })
            val bridge = BrowserToolBridgeImpl(fixture.controller, store, "public-search-fixture")

            val result = BrowserSearchTool.executor(bridge).execute(searchCall())
            assertTrue("search navigation: $result", result is ToolExecutorResult.Completed)
            val output = (result as ToolExecutorResult.Completed).output.jsonObject
            val tabId = output.getValue("tabId").jsonPrimitive.content
            assertEquals("started", output.getValue("status").jsonPrimitive.content)
            assertEquals(
                listOf(tabId),
                bridge
                    .tabs(BrowserObservationContext("public-search-owner", null))
                    .tabs
                    .map { it.tabId },
            )
            val until = SystemClock.elapsedRealtime() + 25_000
            var snapshot = bridge.snapshot(tabId)
            while (SystemClock.elapsedRealtime() < until && !hasResult(snapshot)) {
                SystemClock.sleep(250)
                snapshot = bridge.snapshot(tabId)
            }
            assertTrue("public search snapshot unavailable: ${snapshot.message}", snapshot.ok)
            assertTrue("unexpected search target: ${snapshot.url}", snapshot.url.startsWith("https://www.bing.com/"))
            assertTrue(
                "No OpenAI result link was observable; network, challenge or changed page requires inspection",
                snapshot.nodes.any { it.href.isNotBlank() && it.text.contains("OpenAI", true) },
            )
        }
    }

    private fun hasResult(snapshot: com.helix.tools.browser.SnapshotOutcome): Boolean =
        snapshot.ok && snapshot.nodes.any { it.href.isNotBlank() && it.text.contains("OpenAI", true) }

    private fun searchCall() =
        ExecutableToolCall(
            toolCallId = "public-search",
            toolName = BrowserSearchTool.NAME,
            toolVersion = "1",
            args =
                buildJsonObject {
                    put("engine", "bing")
                    put("query", "OpenAI official website")
                },
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel =
                object : CancelSignal {
                    override fun isCancelled() = false
                },
            sessionId = "public-search-owner",
        )
}
