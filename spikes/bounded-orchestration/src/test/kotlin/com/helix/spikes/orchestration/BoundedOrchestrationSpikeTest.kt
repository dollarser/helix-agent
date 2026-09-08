@file:Suppress("MaxLineLength")

package com.helix.spikes.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOrchestrationSpikeTest {
    private fun coordinator(journal: ChildJournal = InMemoryChildJournal()) =
        BoundedChildCoordinator("parent", ParentBudget(4, 1_000, 8, 60_000), journal)

    @Test fun depthConcurrencyAndTotalAreHardBounds() {
        val c = coordinator()
        assertThrows(IllegalArgumentException::class.java) { c.spawn("deep", 2, "task", byteArrayOf()) }
        c.spawn("a", 1, "a", byteArrayOf())
        c.start("a")
        c.spawn("b", 1, "b", byteArrayOf())
        c.start("b")
        assertThrows(IllegalArgumentException::class.java) { c.spawn("c", 1, "c", byteArrayOf()) }
        c.cancel("a")
        c.spawn("c", 1, "c", byteArrayOf())
        c.cancel("b")
        c.spawn("d", 1, "d", byteArrayOf())
        assertThrows(IllegalArgumentException::class.java) { c.spawn("e", 1, "e", byteArrayOf()) }
    }

    @Test fun childNeverReceivesMutationOrHighRiskTool() {
        val c = coordinator()
        assertThrows(IllegalArgumentException::class.java) {
            c.admitTools(listOf(ReadOnlyTool("write", SpikeOperation.MUTATION, SpikeRisk.L2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            c.admitTools(listOf(ReadOnlyTool("sensitive-read", SpikeOperation.READ_ONLY, SpikeRisk.L2)))
        }
        assertEquals(1, c.admitTools(listOf(ReadOnlyTool("read", SpikeOperation.READ_ONLY, SpikeRisk.L1))).size)
    }

    @Test fun budgetIsChargedToParentAndCannotBeResetByRestart() {
        val journal = InMemoryChildJournal()
        var c = coordinator(journal)
        c.spawn("a", 1, "a", byteArrayOf())
        c.start("a")
        c.complete(
            ChildCompletion("a", 0, "provider", "untrusted", "result", emptyList(), BudgetUsage(4, 1_000, 8, 60_000)),
        )
        c = coordinator(journal)
        assertThrows(IllegalArgumentException::class.java) { c.spawn("b", 1, "b", byteArrayOf()) }
    }

    @Test fun restartNeverReplaysRunningChildAndCompletionOrderIsCallOrder() {
        val journal = InMemoryChildJournal()
        val c = coordinator(journal)
        c.spawn("a", 1, "a", byteArrayOf())
        c.start("a")
        c.spawn("b", 1, "b", byteArrayOf())
        c.start("b")
        val recovered = coordinator(journal).recover()
        assertEquals(listOf(ChildState.NEEDS_REVIEW, ChildState.NEEDS_REVIEW), recovered.map { it.state })
        assertThrows(
            IllegalArgumentException::class.java,
        ) { coordinator(journal).spawn("a", 1, "again", byteArrayOf()) }
    }

    @Test fun completionIsBoundedHasEvidenceHashAndMergesDeterministically() {
        val c = coordinator()
        c.spawn("a", 1, "a", byteArrayOf())
        c.start("a")
        c.spawn("b", 1, "b", byteArrayOf())
        c.start("b")
        c.complete(
            ChildCompletion("b", 1, "provider-b", "untrusted", "B", listOf("artifact:b"), BudgetUsage(tokens = 10)),
        )
        c.complete(
            ChildCompletion("a", 0, "provider-a", "untrusted", "A", listOf("tool-result:a"), BudgetUsage(tokens = 10)),
        )
        assertEquals(listOf("a", "b"), c.mergeCompleted().map { it.childId })
        assertTrue(c.mergeCompleted().all { it.hash.length == 64 && it.trust == "untrusted" })
    }

    @Test fun simultaneousCoordinatorsShareTheAdmissionTransaction() {
        val journal = InMemoryChildJournal()
        val start = java.util.concurrent.CountDownLatch(1)
        val admitted =
            java.util.concurrent.atomic
                .AtomicInteger()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads =
            (0 until 20).map { index ->
                Thread {
                    start.await()
                    try {
                        coordinator(journal).spawn("child-$index", 1, "task", byteArrayOf())
                        admitted.incrementAndGet()
                    } catch (_: IllegalArgumentException) {
                        // Expected admission denial after the two shared slots have been occupied.
                    } catch (failure: Throwable) {
                        errors.add(failure)
                    }
                }.also { it.start() }
            }
        start.countDown()
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive })
        assertTrue(errors.isEmpty())
        assertEquals(2, admitted.get())
        assertEquals(2, journal.load("parent").size)
    }

    @Test fun negativeAndOverflowingUsageCannotCreateBudgetCredit() {
        assertThrows(IllegalArgumentException::class.java) { BudgetUsage(tokens = -1) }
        assertThrows(IllegalArgumentException::class.java) { ParentBudget(-1, 100, 1, 100) }
        assertThrows(ArithmeticException::class.java) { BudgetUsage(tokens = Long.MAX_VALUE) + BudgetUsage(tokens = 1) }
        assertThrows(ArithmeticException::class.java) {
            BudgetUsage(modelCalls = Int.MAX_VALUE) +
                BudgetUsage(modelCalls = 1)
        }
    }

    @Test fun completionsCannotPromoteTheirTrust() {
        val c = coordinator()
        c.spawn("a", 1, "task", byteArrayOf())
        c.start("a")
        assertThrows(IllegalArgumentException::class.java) {
            c.complete(ChildCompletion("a", 0, "provider", "trusted", "result", emptyList(), BudgetUsage()))
        }
    }

    @Test fun workflowRejectsUnknownDependencyCycleAndUnboundedGraph() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowValidator.validate(listOf(WorkflowNode("a", WorkflowNodeType.TOOL, listOf("missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowValidator.validate(
                listOf(
                    WorkflowNode("a", WorkflowNodeType.BARRIER, listOf("b")),
                    WorkflowNode("b", WorkflowNodeType.TOOL, listOf("a")),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowValidator.validate(
                (0..SpikeLimits.MAX_WORKFLOW_NODES).map { WorkflowNode("n$it", WorkflowNodeType.TOOL) },
            )
        }
        WorkflowValidator.validate(
            listOf(
                WorkflowNode("read", WorkflowNodeType.READ_ONLY_DELEGATE),
                WorkflowNode("verify", WorkflowNodeType.VERIFIER, listOf("read")),
            ),
        )
    }
}
