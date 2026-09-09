package com.helix.tools.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

internal class AutomationNodeActionExecutor(
    private val tokenRegistry: NodeTokenRegistry,
    private val sensitiveTargetPolicy: SensitiveAutomationTargetPolicy = SensitiveAutomationTargetPolicy,
    private val sensitiveSemanticPolicy: SensitiveAutomationSemanticPolicy =
        SensitiveAutomationSemanticPolicy,
) {
    @Suppress("ReturnCount")
    fun execute(
        root: SnapshotNode?,
        session: ActiveAutomationSession,
        generation: Long,
        request: AutomationNodeActionRequest,
    ): AutomationActionResult {
        if (request.token.length != TOKEN_HEX_LENGTH) {
            root?.recycleSafely()
            return result(AutomationActionStatus.TOKEN_UNKNOWN)
        }
        val lookup = tokenRegistry.lookup(request.token)
        if (lookup.status != NodeTokenLookupStatus.VALID) {
            root?.recycleSafely()
            return result(
                if (lookup.status == NodeTokenLookupStatus.EXPIRED) {
                    AutomationActionStatus.TOKEN_EXPIRED
                } else {
                    AutomationActionStatus.TOKEN_UNKNOWN
                },
            )
        }
        val binding = checkNotNull(lookup.binding)
        if (root == null) return result(AutomationActionStatus.UNSUPPORTED_UI)

        val targetNode =
            root.walkOwned(binding.path)
                ?: return result(AutomationActionStatus.STALE_TOKEN)
        return try {
            val observed = targetNode.observe()
            validateObservedTarget(observed, session, generation, binding)?.let(::result)
                ?: executeValidated(targetNode, observed, request)
        } catch (_: RuntimeException) {
            result(AutomationActionStatus.UNSUPPORTED_UI)
        } finally {
            targetNode.recycleSafely()
        }
    }

    private fun validateObservedTarget(
        observed: ObservedSnapshotNode,
        session: ActiveAutomationSession,
        generation: Long,
        binding: NodeTokenBinding,
    ): AutomationActionStatus? =
        when {
            observed.packageName != binding.packageName || observed.windowId != binding.windowId -> {
                AutomationActionStatus.TARGET_CHANGED
            }

            binding.packageName !in session.scope.allowedPackages -> {
                AutomationActionStatus.TARGET_NOT_ALLOWLISTED
            }

            sensitiveTargetPolicy.isDeniedPackage(binding.packageName) ||
                observed.password ||
                observed.accessibilityDataSensitive -> {
                AutomationActionStatus.SENSITIVE_UI
            }

            generation != binding.generation -> {
                AutomationActionStatus.STALE_TOKEN
            }

            observed.fingerprint(binding.packageName, binding.windowId, binding.path) !=
                binding.fingerprint -> {
                AutomationActionStatus.STALE_TOKEN
            }

            else -> {
                null
            }
        }

    @Suppress("ReturnCount")
    private fun executeValidated(
        node: SnapshotNode,
        observed: ObservedSnapshotNode,
        request: AutomationNodeActionRequest,
    ): AutomationActionResult {
        if (
            request.action == AutomationNodeAction.SET_TEXT &&
            (request.text == null || request.text.length > MAX_SET_TEXT)
        ) {
            return result(AutomationActionStatus.INVALID_ARGUMENT)
        }
        if (sensitiveSemanticPolicy.isDenied(observed, request.action)) {
            return result(AutomationActionStatus.SENSITIVE_UI)
        }
        val actionAndArguments =
            actionAndArguments(observed, request)
                ?: return result(AutomationActionStatus.ACTION_NOT_SUPPORTED)
        val performed = node.performAction(actionAndArguments.first, actionAndArguments.second)
        if (!performed) return result(AutomationActionStatus.ACTION_FAILED)
        tokenRegistry.invalidate()
        return result(AutomationActionStatus.SUCCEEDED)
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun actionAndArguments(
        observed: ObservedSnapshotNode,
        request: AutomationNodeActionRequest,
    ): Pair<Int, Bundle?>? =
        when (request.action) {
            AutomationNodeAction.CLICK -> {
                AccessibilityNodeInfo.ACTION_CLICK.takeIf { observed.enabled && observed.clickable }?.to(null)
            }

            AutomationNodeAction.LONG_CLICK -> {
                AccessibilityNodeInfo.ACTION_LONG_CLICK
                    .takeIf { observed.enabled && observed.longClickable }
                    ?.to(null)
            }

            AutomationNodeAction.SET_TEXT -> {
                val text = request.text
                if (observed.enabled && observed.editable && text != null && text.length <= MAX_SET_TEXT) {
                    AccessibilityNodeInfo.ACTION_SET_TEXT to
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                text,
                            )
                        }
                } else {
                    null
                }
            }

            AutomationNodeAction.SCROLL_FORWARD -> {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD.takeIf { observed.enabled && observed.scrollable }?.to(null)
            }

            AutomationNodeAction.SCROLL_BACKWARD -> {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD.takeIf { observed.enabled && observed.scrollable }?.to(
                    null,
                )
            }
        }

    @Suppress("ReturnCount")
    private fun SnapshotNode.walkOwned(path: List<Int>): SnapshotNode? {
        var current: SnapshotNode? = this
        for (index in path) {
            val parent = current ?: return null
            val child =
                try {
                    parent.childAt(index)
                } catch (_: RuntimeException) {
                    null
                }
            parent.recycleSafely()
            current = child
            if (current == null) return null
        }
        return current
    }

    private fun SnapshotNode.recycleSafely() {
        try {
            recycle()
        } catch (_: RuntimeException) {
            // A platform recycle failure never upgrades an action to success.
        }
    }

    private fun result(status: AutomationActionStatus) = AutomationActionResult(status)

    companion object {
        private const val TOKEN_HEX_LENGTH = 32
        private const val MAX_SET_TEXT = 2_000
    }
}
