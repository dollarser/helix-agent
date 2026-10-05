package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationFindQuery
import com.helix.extensions.mobileuse.automation.AutomationFinder
import com.helix.extensions.mobileuse.automation.AutomationNodeAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.extensions.mobileuse.automation.AutomationWaitCondition
import com.helix.extensions.mobileuse.automation.AutomationWaitResult
import com.helix.extensions.mobileuse.automation.AutomationWaitStatus
import com.helix.extensions.mobileuse.automation.AutomationWaiter
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import java.time.Duration
import java.time.Instant

internal class AutomationClickMatchExecutor(
    private val port: AutomationToolPort,
) {
    fun execute(
        call: ExecutableToolCall,
        query: AutomationFindQuery,
        timeoutMillis: Int,
        pollMillis: Int,
        packageName: String? = null,
    ): ToolExecutorResult {
        val preparation = prepareObservation(call, query, timeoutMillis, pollMillis)
        val observation = preparation.observation
        val snapshot = observation?.snapshot
        return when {
            preparation.terminal != null -> {
                preparation.terminal
            }

            observation == null -> {
                ToolExecutorResult.Failed("TARGET_NOT_FOUND", sideEffectFree = true)
            }

            snapshot == null -> {
                ToolExecutorResult.Failed(observation.status.name, sideEffectFree = true)
            }

            packageName != null && snapshot.packageName != packageName -> {
                ToolExecutorResult.Failed("TARGET_CHANGED", sideEffectFree = true)
            }

            else -> {
                clickUnique(snapshot, query)
            }
        }
    }

    private fun prepareObservation(
        call: ExecutableToolCall,
        query: AutomationFindQuery,
        timeoutMillis: Int,
        pollMillis: Int,
    ): Preparation =
        if (timeoutMillis <= 0) {
            Preparation(observation = port.snapshot())
        } else {
            waitForObservation(call, query, timeoutMillis, pollMillis)
        }

    private fun waitForObservation(
        call: ExecutableToolCall,
        query: AutomationFindQuery,
        timeoutMillis: Int,
        pollMillis: Int,
    ): Preparation {
        val available = Duration.between(Instant.now(), call.deadline)
        if (available.isNegative || available.isZero) {
            return Preparation(terminal = ToolExecutorResult.TimedOut)
        }
        val waited =
            AutomationWaiter().waitFor(
                query = query,
                timeout = minOf(Duration.ofMillis(timeoutMillis.toLong()), available),
                pollInterval = Duration.ofMillis(pollMillis.toLong()),
                condition = AutomationWaitCondition.PRESENT,
                cancelled = { call.cancel.isCancelled() },
                snapshotProvider = port::snapshot,
            )
        return Preparation(
            observation = waited.observation,
            terminal = waitFailure(waited),
        )
    }

    private fun waitFailure(waited: AutomationWaitResult): ToolExecutorResult? =
        when (waited.status) {
            AutomationWaitStatus.CANCELLED -> {
                ToolExecutorResult.Cancelled
            }

            AutomationWaitStatus.INVALID_ARGUMENT -> {
                ToolExecutorResult.Failed("AUTOMATION_ARGUMENT_INVALID", sideEffectFree = true)
            }

            AutomationWaitStatus.SNAPSHOT_REFUSED -> {
                ToolExecutorResult.Failed(
                    waited.observation?.status?.name ?: waited.status.name,
                    sideEffectFree = true,
                )
            }

            AutomationWaitStatus.TIMED_OUT -> {
                ToolExecutorResult.Failed("TARGET_NOT_FOUND", sideEffectFree = true)
            }

            else -> {
                null
            }
        }

    private fun clickUnique(
        snapshot: AutomationSnapshot,
        query: AutomationFindQuery,
    ): ToolExecutorResult {
        val matches = AutomationFinder.findAll(snapshot, query)
        val failure =
            when {
                matches == null -> {
                    "INVALID_QUERY"
                }

                matches.isEmpty() -> {
                    "TARGET_NOT_FOUND"
                }

                matches.all(::automationNodeOffscreen) -> {
                    "TARGET_OFFSCREEN: scroll a fresh scrollable container with ui.scroll, " +
                        "then observe again before clicking; do not guess coordinates."
                }

                else -> {
                    null
                }
            }
        if (failure != null) return ToolExecutorResult.Failed(failure, sideEffectFree = true)

        val nodeIndex = snapshot.nodes.associateBy { it.token }
        val targets =
            requireNotNull(matches)
                .map { automationClickTargetToken(it, nodeIndex) }
                .filter(String::isNotEmpty)
                .distinct()
        val targetFailure =
            when {
                targets.isEmpty() -> "TARGET_NOT_CLICKABLE"
                targets.size != 1 -> "TARGET_AMBIGUOUS"
                else -> null
            }
        return if (targetFailure != null) {
            ToolExecutorResult.Failed(targetFailure, sideEffectFree = true)
        } else {
            port
                .nodeAction(
                    AutomationNodeActionRequest(
                        action = AutomationNodeAction.CLICK,
                        token = targets.single(),
                    ),
                ).toToolOutcome()
        }
    }

    private data class Preparation(
        val observation: AutomationSnapshotResult? = null,
        val terminal: ToolExecutorResult? = null,
    )
}
