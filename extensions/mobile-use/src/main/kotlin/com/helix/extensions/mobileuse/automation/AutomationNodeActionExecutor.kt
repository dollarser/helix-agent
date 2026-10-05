package com.helix.extensions.mobileuse.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.helix.extensions.mobileuse.R

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
            return result(AutomationActionStatus.TOKEN_UNKNOWN)
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

            !session.scope.permitsPackage(binding.packageName) -> {
                AutomationActionStatus.TARGET_NOT_ALLOWLISTED
            }

            (
                !session.scope.allApplications &&
                    sensitiveTargetPolicy.isDeniedPackage(binding.packageName, session.allowSystemSettings)
            ) ||
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
        argumentFailure(observed, request)?.let { return result(it) }
        if (sensitiveSemanticPolicy.isDenied(observed, request.action)) {
            return result(AutomationActionStatus.SENSITIVE_UI)
        }
        if (request.action == AutomationNodeAction.SET_TEXT && request.submit && !observed.canImeEnter) {
            return result(AutomationActionStatus.ACTION_NOT_SUPPORTED)
        }
        if (request.action == AutomationNodeAction.IME_ENTER && !supportsImeEnter(observed)) {
            return result(AutomationActionStatus.ACTION_NOT_SUPPORTED)
        }
        val actionAndArguments =
            if (request.action == AutomationNodeAction.IME_ENTER) {
                null
            } else {
                actionAndArguments(observed, request)
                    ?: return result(AutomationActionStatus.ACTION_NOT_SUPPORTED)
            }
        return try {
            performPlatformAutomationAction { dispatchNodeAction(node, request, actionAndArguments) }
        } finally {
            // Every attempted action requires a new observation, including lost acknowledgements.
            tokenRegistry.invalidate()
        }
    }

    private fun supportsImeEnter(observed: ObservedSnapshotNode): Boolean =
        observed.enabled && observed.editable && observed.canImeEnter

    private fun argumentFailure(
        observed: ObservedSnapshotNode,
        request: AutomationNodeActionRequest,
    ): AutomationActionStatus? =
        when {
            request.action == AutomationNodeAction.SET_TEXT &&
                (request.text == null || request.text.length > MAX_SET_TEXT) -> {
                AutomationActionStatus.INVALID_ARGUMENT
            }

            request.action == AutomationNodeAction.SET_PROGRESS &&
                request.progress?.let { it.isFinite() && observed.range?.accepts(it) != false } != true -> {
                AutomationActionStatus.INVALID_ARGUMENT
            }

            else -> {
                null
            }
        }

    private fun dispatchNodeAction(
        node: SnapshotNode,
        request: AutomationNodeActionRequest,
        actionAndArguments: Pair<Int, Bundle?>?,
    ): Boolean =
        when (request.action) {
            AutomationNodeAction.SET_PROGRESS -> {
                node.setProgress(requireNotNull(request.progress).toFloat())
            }

            AutomationNodeAction.SET_TEXT -> {
                node.setText(requireNotNull(request.text)) && (!request.submit || node.imeEnter())
            }

            AutomationNodeAction.IME_ENTER -> {
                node.imeEnter()
            }

            else -> {
                val platformAction = checkNotNull(actionAndArguments)
                node.performAction(platformAction.first, platformAction.second)
            }
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
                    AccessibilityNodeInfo.ACTION_SET_TEXT to null
                } else {
                    null
                }
            }

            AutomationNodeAction.IME_ENTER -> {
                null
            }

            AutomationNodeAction.SET_PROGRESS -> {
                val progress = request.progress
                if (observed.enabled && observed.canSetProgress && progress != null &&
                    observed.range?.accepts(progress) == true
                ) {
                    android.R.id.accessibilityActionSetProgress to null
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

        // UTF-16 text plus Bundle overhead remains well below Android Binder transaction limits.
        const val MAX_SET_TEXT = AUTOMATION_MAX_SET_TEXT
    }
}
