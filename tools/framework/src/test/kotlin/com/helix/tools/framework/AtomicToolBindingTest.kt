package com.helix.tools.framework

import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AtomicToolBindingTest {
    private fun executor() =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall) = ToolExecutorResult.Cancelled
        }

    private fun binding(
        name: String,
        revision: String = "implementation-1",
    ) = ToolBinding(TimeNowTool.descriptor().copy(name = ToolName(name)), executor(), revision)

    @Test fun replacementAndRemovalRejectOldRefsWhileUnrelatedPublishPreservesThem() {
        val registry = ToolRegistry()
        val first = binding("first")
        val ref = registry.registerBatch(listOf(first)).single().ref
        registry.registerBatch(listOf(binding("other")))
        assertSame(first.executor, registry.resolveBinding(ref)?.executor)
        val unchanged = registry.replaceOwner(first.owner, listOf(first)).single()
        assertEquals(ref, unchanged.ref)
        val replacement = first.copy(executor = executor(), implementationRevision = "implementation-2")
        val next = registry.replaceOwner(first.owner, listOf(replacement)).single()
        assertNull(registry.resolveBinding(ref))
        assertNotEquals(ref, next.ref)
        registry.replaceOwner(first.owner, emptyList())
        assertNull(registry.resolveBinding(next.ref))
        var entered = false
        assertNull(
            registry.admit(next.ref) {
                entered = true
                true
            },
        )
        assertFalse(entered)
    }

    @Test fun duplicateOrCrossOwnerCollisionLeavesEntireOriginalSnapshot() {
        val registry = ToolRegistry()
        val original = binding("first")
        registry.registerBatch(listOf(original))
        val before = registry.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            registry.replaceOwner(original.owner, listOf(original, original))
        }
        assertEquals(before, registry.snapshot())
        val foreign =
            original.copy(
                descriptor =
                    original.descriptor.copy(
                        origin = ToolOrigin.PluginOrigin("other", "1", "native"),
                    ),
            )
        assertThrows(IllegalArgumentException::class.java) {
            registry.replaceOwner(foreign.owner, listOf(foreign))
        }
        assertEquals(before, registry.snapshot())
    }

    @Test fun concurrentReadersOnlySeeCompleteBatches() {
        val registry = ToolRegistry()
        val a = listOf(binding("first", "a"), binding("second", "a"))
        val b = listOf(binding("first", "b"), binding("second", "b"))
        registry.registerBatch(a)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val writer =
            thread {
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(1000) { registry.replaceOwner(a.first().owner, if (it % 2 == 0) b else a) }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        start.countDown()
        try {
            repeat(1000) {
                val snapshot = registry.snapshot()
                assertEquals(2, snapshot.size)
                assertEquals(1, snapshot.map { it.ref.implementationRevision }.distinct().size)
                assertEquals(snapshot.map { it.descriptor.name }, snapshot.map { it.ref.name })
            }
        } finally {
            writer.join(5000)
        }
        assertFalse(writer.isAlive)
        failure.get()?.let { throw AssertionError(it) }
    }

    @Test fun preparationFailureKeepsOldBindingAndDoesNotBlockAdmission() {
        val registry = ToolRegistry()
        val original = binding("first")
        val old = registry.registerBatch(listOf(original)).single()
        val preparing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val writer =
            thread {
                try {
                    registry.replaceOwner(original.owner, listOf(binding("first", "v2"))) {
                        preparing.countDown()
                        check(finish.await(5, TimeUnit.SECONDS))
                        error("storage commit failed")
                    }
                } catch (failure: Throwable) {
                    error.set(failure)
                }
            }
        try {
            check(preparing.await(5, TimeUnit.SECONDS))
            assertEquals(true, registry.admit(old.ref) { true })
            assertEquals(listOf(old), registry.snapshot())
        } finally {
            finish.countDown()
            writer.join(5000)
        }
        assertFalse(writer.isAlive)
        assertEquals("storage commit failed", error.get()?.message)
        assertSame(old, registry.resolveBinding(old.ref))
    }

    @Test fun admissionBeforeRevocationKeepsTheOriginalExecutionLease() {
        val registry = ToolRegistry()
        val original = binding("first")
        val old = registry.registerBatch(listOf(original)).single()
        val admitted = registry.admit(old.ref) { old }
        registry.replaceOwner(original.owner, listOf(binding("first", "v2")))
        assertNull(registry.admit(old.ref) { old })
        assertSame(original.executor, admitted?.executor)
    }

    @Test fun stableNativeRevisionSurvivesRegistryRestartButNotHostReplacement() {
        val candidate = binding("first")
        val a = ToolRegistry("install-1").registerBatch(listOf(candidate)).single().ref
        val b = ToolRegistry("install-1").registerBatch(listOf(candidate)).single().ref
        val c = ToolRegistry("install-2").registerBatch(listOf(candidate)).single().ref
        assertEquals(a.implementationRevision, b.implementationRevision)
        assertNotEquals(a.incarnation, b.incarnation)
        assertNotEquals(a.implementationRevision, c.implementationRevision)
    }

    @Test fun recursivePublicationCannotSilentlyOverwriteAnotherCommit() {
        val registry = ToolRegistry()
        val original = binding("first")
        val before = registry.registerBatch(listOf(original))
        assertThrows(IllegalStateException::class.java) {
            registry.replaceOwner(original.owner, listOf(binding("first", "v2"))) {
                registry.registerBatch(listOf(binding("nested")))
            }
        }
        assertEquals(before, registry.snapshot())
        registry.registerBatch(listOf(binding("after-failure")))
        assertEquals(2, registry.snapshot().size)
    }

    @Test fun publishedSchemasAndCapabilitiesAreDetachedFromMutableProducerCollections() {
        val properties =
            mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "path" to
                    kotlinx.serialization.json.JsonObject(
                        mapOf("type" to kotlinx.serialization.json.JsonPrimitive("string")),
                    ),
            )
        val capabilities =
            mutableSetOf(
                com.helix.core.model.Capability.entries
                    .first(),
            )
        val candidate =
            binding("first").let {
                it.copy(
                    descriptor =
                        it.descriptor.copy(
                            inputSchema =
                                kotlinx.serialization.json.JsonObject(
                                    mapOf(
                                        "type" to kotlinx.serialization.json.JsonPrimitive("object"),
                                        "properties" to kotlinx.serialization.json.JsonObject(properties),
                                    ),
                                ),
                            requiredCapabilities = capabilities,
                        ),
                )
            }
        val registry = ToolRegistry()
        val published = registry.registerBatch(listOf(candidate)).single()
        properties.clear()
        capabilities.clear()
        assertEquals(published.ref.contractHash, published.descriptor.contractHash.hex)
        assertEquals(1, published.descriptor.requiredCapabilities.size)
        assertEquals(1, (published.descriptor.inputSchema["properties"] as kotlinx.serialization.json.JsonObject).size)
        assertThrows(UnsupportedOperationException::class.java) {
            (published.descriptor.requiredCapabilities as MutableSet).clear()
        }
    }
}
