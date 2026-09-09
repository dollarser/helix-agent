package com.helix.tools.automation

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
