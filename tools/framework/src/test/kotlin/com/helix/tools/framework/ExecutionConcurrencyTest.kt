package com.helix.tools.framework

import com.helix.core.model.ExecutionTargetType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExecutionConcurrencyTest {
    private class Store : ExecutionOwnership.Store {
        private var state = emptySet<ExecutionOwnership.Owner>()

        override fun owners() = state

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean {
            if (expected != state) return false
            state = replacement
            return true
        }
    }

    private val host = ExecutionOwnership(Store())
    private val nativeOwner = ExecutionOwnership.Owner("quickjs-native:original", "generation")

    private fun call(
        id: String,
        native: Boolean = false,
    ) = ExecutableToolCall(
        id,
        "code.javascript.run",
        "1",
        buildJsonObject { put("access", if (native) "native" else "isolated") },
        ExecutionTargetType.LOCAL_QUICKJS,
        Instant.now().plusSeconds(10),
        NoCancellation,
        "s",
        "t",
    )

    private fun executor(action: (ExecutableToolCall) -> Unit = {}) =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                action(call)
                return ToolExecutorResult.Completed(buildJsonObject { put("ok", true) })
            }
        }

    @Test fun retainedNativeBlocksOnlyNativeEngineAndNotIsolatedOrBashOrTime() {
        requireNotNull(host.acquire("launch")).use { assertTrue(it.retain(nativeOwner)) }
        val wrapped = host.guard(host.nativeExecutor(executor()))
        assertTrue(wrapped.execute(call("native", true)) is ToolExecutorResult.Failed)
        assertTrue(wrapped.execute(call("isolated")) is ToolExecutorResult.Completed)
        assertTrue(host.guard(executor()).execute(call("bash")) is ToolExecutorResult.Completed)
        assertTrue(
            host
                .guard(
                    TimeNowTool.executor(
                        object : com.helix.core.model.Clock {
                            override fun now() = Instant.EPOCH
                        },
                    ),
                ).execute(call("time")) is ToolExecutorResult.Completed,
        )
        assertEquals(setOf(nativeOwner), host.retainedOwners())
        assertTrue(host.settle(nativeOwner))
        assertTrue(wrapped.execute(call("native-after-exit", true)) is ToolExecutorResult.Completed)
    }

    @Test fun activeNativeProtocolCannotBeEnteredTwiceButIsolatedWorkContinues() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val wrapped =
            host.guard(
                host.nativeExecutor(
                    executor {
                        if (it.toolCallId == "first") {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    },
                ),
            )
        val first =
            Thread {
                try {
                    wrapped.execute(call("first", true))
                } finally {
                    done.countDown()
                }
            }
        first.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(wrapped.execute(call("second", true)) is ToolExecutorResult.Failed)
            assertTrue(wrapped.execute(call("isolated")) is ToolExecutorResult.Completed)
            assertFalse(done.await(20, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            first.join(5000)
        }
        assertTrue(done.await(1, TimeUnit.SECONDS))
    }

    @Test fun controlForOneJobNeverLocksAnotherJobAndDuplicateImporterStillFails() {
        val a = ExecutionOwnership.Owner("a", "ga")
        val b = ExecutionOwnership.Owner("b", "gb")
        requireNotNull(host.acquire("la")).use { assertTrue(it.retain(a)) }
        requireNotNull(host.acquire("lb")).use { assertTrue(it.retain(b)) }
        requireNotNull(host.acquireReconciliation(a)).use { permit ->
            assertTrue(permit.settle())
            val duplicate = host.controlExecutor({ a }) { _, _ -> error("must not run duplicate importer") }
            assertTrue(host.guard(duplicate).execute(call("ca")) is ToolExecutorResult.Failed)
            requireNotNull(host.acquireReconciliation(b)).use { assertTrue(it.settle()) }
            assertTrue(host.guard(executor()).execute(call("write")) is ToolExecutorResult.Completed)
        }
        assertTrue(host.retainedOwners().isEmpty())
    }
}
