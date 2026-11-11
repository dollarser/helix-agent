package com.helix.feature.browser

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.browser.BrowserSearchTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Real Android bridge boundary; no public search traffic or account/quota use. */
class BrowserSearchDeviceTest {
    @Test
    fun queuedOpenRechecksCancellationAndRetiresTimeoutAndInterruptedWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var controller: BrowserController
        instrumentation.runOnMainSync { controller = BrowserController(instrumentation.targetContext) }
        runBlocking { controller.sessionReady.await() }
        instrumentation.runOnMainSync { controller.closeAllTabs() }
        val store = WorkspaceArtifactStore(ScopeRootResolver { instrumentation.targetContext.cacheDir.toPath() })
        val bridge = BrowserToolBridgeImpl(controller, store, "queued-search-fixture")
        try {
            for (mode in listOf("cancel", "timeout", "interrupt")) {
                runQueuedOpen(bridge, mode)
                instrumentation.runOnMainSync {
                    assertTrue(
                        controller.state.value.tabs
                            .isEmpty(),
                    )
                }
            }
        } finally {
            instrumentation.runOnMainSync { controller.destroy() }
        }
    }

    private fun runQueuedOpen(
        bridge: BrowserToolBridgeImpl,
        mode: String,
    ) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val active =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val outcome =
            java.util.concurrent.atomic
                .AtomicReference<String>()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            entered.countDown()
            release.await(6, java.util.concurrent.TimeUnit.SECONDS)
        }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val worker =
            Thread {
                try {
                    outcome.set(bridge.open("", "search-owner", active::get).failureReason)
                } catch (_: InterruptedException) {
                    outcome.set("interrupted")
                }
            }
        worker.start()
        try {
            android.os.SystemClock.sleep(100)
            when (mode) {
                "cancel" -> {
                    active.set(false)
                    release.countDown()
                }

                "interrupt" -> {
                    worker.interrupt()
                }
            }
            worker.join(4000)
            assertTrue(!worker.isAlive)
            assertEquals(
                when (mode) {
                    "cancel" -> "browser-open-inactive"
                    "interrupt" -> "interrupted"
                    else -> "browser-open-outcome-unknown"
                },
                outcome.get(),
            )
        } finally {
            release.countDown()
            worker.join(1000)
        }
    }

    @Test
    fun missingHostAndCancelledSearchDoNotCreateTabsOrClaimResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var controller: BrowserController
        instrumentation.runOnMainSync { controller = BrowserController(instrumentation.targetContext) }
        runBlocking { controller.sessionReady.await() }
        instrumentation.runOnMainSync { controller.closeAllTabs() }
        val store = WorkspaceArtifactStore(ScopeRootResolver { instrumentation.targetContext.cacheDir.toPath() })
        val bridge = BrowserToolBridgeImpl(controller, store, "search-fixture")
        val call =
            ExecutableToolCall(
                toolCallId = "search-call",
                toolName = BrowserSearchTool.NAME,
                toolVersion = "1",
                args =
                    buildJsonObject {
                        put("engine", "bing")
                        put("query", "Helix 搜索")
                    },
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                deadline = Instant.now().plusSeconds(30),
                cancel =
                    object : CancelSignal {
                        override fun isCancelled() = false
                    },
                sessionId = "search-owner",
            )
        try {
            val result = BrowserSearchTool.executor(bridge).execute(call) as ToolExecutorResult.Failed
            assertEquals("browser-host-unavailable", result.detail)
            assertEquals(
                ToolExecutorResult.Cancelled,
                BrowserSearchTool.executor(bridge).execute(
                    call.copy(
                        cancel =
                            object : CancelSignal {
                                override fun isCancelled() = true
                            },
                    ),
                ),
            )
            assertTrue(
                controller.state.value.tabs
                    .isEmpty(),
            )
        } finally {
            instrumentation.runOnMainSync { controller.destroy() }
        }
    }
}
