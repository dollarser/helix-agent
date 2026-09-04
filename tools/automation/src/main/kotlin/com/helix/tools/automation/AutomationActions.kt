package com.helix.tools.automation

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.helix.core.model.Clock
import com.helix.core.model.SystemClock
import java.time.Duration

enum class AutomationTextMatch {
    EXACT,
    CONTAINS,
}

data class AutomationFindQuery(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val match: AutomationTextMatch = AutomationTextMatch.EXACT,
    val maxResults: Int = 20,
)

enum class AutomationFindStatus {
    FOUND,
    NOT_FOUND,
    INVALID_QUERY,
}

data class AutomationFindResult(
    val status: AutomationFindStatus,
    val nodes: List<AutomationSnapshotNode> = emptyList(),
)

object AutomationFinder {
    fun find(
        snapshot: AutomationSnapshot,
        query: AutomationFindQuery,
    ): AutomationFindResult {
        if (!query.isValid()) return AutomationFindResult(AutomationFindStatus.INVALID_QUERY)
        val matches = snapshot.nodes.filter { it.matches(query) }.take(query.maxResults)
        return AutomationFindResult(
            status =
                if (matches.isEmpty()) {
                    AutomationFindStatus.NOT_FOUND
                } else {
                    AutomationFindStatus.FOUND
                },
            nodes = matches,
        )
    }

    private fun AutomationFindQuery.isValid(): Boolean =
        maxResults in 1..MAX_FIND_RESULTS &&
            listOf(text, contentDescription, viewId, className).all { it == null || it.isNotBlank() } &&
            (
                text != null ||
                    contentDescription != null ||
                    viewId != null ||
                    className != null ||
                    clickable != null
            )

    private fun AutomationSnapshotNode.matches(query: AutomationFindQuery): Boolean =
        text.matches(query.text, query.match) &&
            contentDescription.matches(query.contentDescription, query.match) &&
            viewId.matches(query.viewId, query.match) &&
            className.matches(query.className, query.match) &&
            (query.clickable == null || clickable == query.clickable)

    @Suppress("ReturnCount")
    private fun String?.matches(
        expected: String?,
        match: AutomationTextMatch,
    ): Boolean {
        if (expected == null) return true
        val actual = this ?: return false
        return when (match) {
            AutomationTextMatch.EXACT -> actual == expected
            AutomationTextMatch.CONTAINS -> actual.contains(expected)
        }
    }

    private const val MAX_FIND_RESULTS = 50
}

enum class AutomationNodeAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

enum class AutomationGlobalAction {
    BACK,
    HOME,
}

data class AutomationNodeActionRequest(
    val action: AutomationNodeAction,
    val token: String,
    val text: String? = null,
)

enum class AutomationActionStatus {
    SUCCEEDED,
    SERVICE_NOT_CONNECTED,
    NO_ACTIVE_SESSION,
    SESSION_PAUSED,
    CHECKPOINT_REQUIRED,
    ACTION_BUDGET_EXHAUSTED,
    TOKEN_UNKNOWN,
    TOKEN_EXPIRED,
    STALE_TOKEN,
    TARGET_CHANGED,
    TARGET_NOT_ALLOWLISTED,
    SENSITIVE_UI,
    UNSUPPORTED_UI,
    ACTION_NOT_SUPPORTED,
    INVALID_ARGUMENT,
    ACTION_FAILED,
}

data class AutomationActionResult(
    val status: AutomationActionStatus,
)

enum class AutomationResumeStatus {
    RESUMED,
    SERVICE_NOT_CONNECTED,
    NO_ACTIVE_SESSION,
    NOT_PAUSED,
    TARGET_NOT_ALLOWLISTED,
    SNAPSHOT_REFUSED,
    TARGET_MISMATCH,
}

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

internal fun interface SensitiveAutomationSemanticPolicy {
    fun isDenied(
        node: ObservedSnapshotNode,
        action: AutomationNodeAction?,
    ): Boolean

    companion object : SensitiveAutomationSemanticPolicy {
        private val clickTerms =
            setOf(
                "pay",
                "payment",
                "transfer",
                "purchase",
                "buy now",
                "send",
                "publish",
                "delete account",
                "authorize",
                "authorization",
                "grant permission",
                "allow install",
                "enable root",
                "支付",
                "转账",
                "购买",
                "发送",
                "发布",
                "删除账号",
                "删除账户",
                "授权",
                "允许安装",
                "开启root",
            )
        private val textInputTerms =
            setOf(
                "password",
                "passwd",
                "passcode",
                "pin",
                "otp",
                "cvv",
                "verification code",
                "authentication code",
                "account number",
                "card number",
                "payment",
                "transfer",
                "密码",
                "口令",
                "验证码",
                "认证码",
                "银行卡",
                "卡号",
                "支付",
                "转账",
            )

        override fun isDenied(
            node: ObservedSnapshotNode,
            action: AutomationNodeAction?,
        ): Boolean {
            val semantics =
                listOf(node.text, node.contentDescription, node.viewId, node.className)
                    .filterNotNull()
                    .joinToString(" ")
                    .lowercase()
            return when (action) {
                AutomationNodeAction.CLICK,
                AutomationNodeAction.LONG_CLICK,
                -> semantics.matchesSensitiveTerms(clickTerms)

                AutomationNodeAction.SET_TEXT -> semantics.matchesSensitiveTerms(textInputTerms)

                AutomationNodeAction.SCROLL_FORWARD,
                AutomationNodeAction.SCROLL_BACKWARD,
                -> false

                null -> node.password || node.accessibilityDataSensitive
            }
        }

        private fun String.matchesSensitiveTerms(terms: Set<String>): Boolean {
            val asciiTokens = Regex("[a-z0-9]+").findAll(this).map { it.value }.toSet()
            val normalizedPhrase = replace(Regex("[^a-z0-9\\p{IsHan}]+"), " ").trim()
            return terms.any { term ->
                when {
                    term.any { it.code > 127 } -> contains(term)
                    ' ' in term -> normalizedPhrase.contains(term)
                    else -> term in asciiTokens
                }
            }
        }
    }
}

enum class AutomationWaitStatus {
    FOUND,
    TIMED_OUT,
    INVALID_ARGUMENT,
    SNAPSHOT_REFUSED,
}

data class AutomationWaitResult(
    val status: AutomationWaitStatus,
    val matches: List<AutomationSnapshotNode> = emptyList(),
)

/** Bounded polling primitive. Callers must run it off the Android main thread. */
class AutomationWaiter(
    private val clock: Clock = SystemClock(),
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    @Suppress("ReturnCount", "ComplexCondition")
    fun waitFor(
        query: AutomationFindQuery,
        timeout: Duration,
        pollInterval: Duration = Duration.ofMillis(200),
        snapshotProvider: () -> AutomationSnapshotResult,
    ): AutomationWaitResult {
        if (
            timeout.isNegative ||
            timeout.isZero ||
            timeout > MAX_TIMEOUT ||
            pollInterval < MIN_POLL ||
            pollInterval > MAX_POLL
        ) {
            return AutomationWaitResult(AutomationWaitStatus.INVALID_ARGUMENT)
        }
        val deadline = clock.now().plus(timeout)
        while (clock.now().isBefore(deadline)) {
            val result = snapshotProvider()
            if (result.status != AutomationSnapshotStatus.SUCCESS || result.snapshot == null) {
                return AutomationWaitResult(AutomationWaitStatus.SNAPSHOT_REFUSED)
            }
            val found = AutomationFinder.find(result.snapshot, query)
            if (found.status == AutomationFindStatus.INVALID_QUERY) {
                return AutomationWaitResult(AutomationWaitStatus.INVALID_ARGUMENT)
            }
            if (found.status == AutomationFindStatus.FOUND) {
                return AutomationWaitResult(AutomationWaitStatus.FOUND, found.nodes)
            }
            sleeper(pollInterval)
        }
        return AutomationWaitResult(AutomationWaitStatus.TIMED_OUT)
    }

    companion object {
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(10)
        val MIN_POLL: Duration = Duration.ofMillis(50)
        val MAX_POLL: Duration = Duration.ofSeconds(1)
    }
}
