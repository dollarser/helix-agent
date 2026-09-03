package com.helix.app.chat

import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence

/**
 * Pre-send data-egress gating (ADR-0005 + doc 10 section 2.6, M2 visibility).
 *
 * Every outgoing provider request carries an auditable [EgressSummary]
 * (provider id, protocol, canonical origin, residence, data categories,
 * scope). The chat send path's content sources are user-typed text
 * ([OutgoingContent.UserText]) and — since HXA-049 (ADR-0014) — staged chat
 * attachments ([OutgoingContent.FileText], which force high-sensitivity
 * confirmation); the other high-sensitivity sources (contacts, …) enter
 * context in later milestones. The gate mechanism below is complete and
 * exercised by tests for the categories it must handle.
 *
 * What is NOT here (HXA-033, later milestone): stored Advanced rules with
 * 1h/24h/7d/30d expiry, clock-rewind fail-closed re-confirmation, and the
 * generic Policy engine. In M2 BOTH profiles confirm per send for
 * high-sensitivity content (STANDARD never offers permanent allow — and
 * neither does M2 Advanced, which says so honestly instead of faking a
 * "gated" state).
 */
object EgressDisclosure {
    /** Data categories (ADR-0005 / doc 10 section 2.6). [label] is the user-visible Chinese name. */
    enum class DataCategory(
        val label: String,
    ) {
        REGULAR("普通内容"),
        HIGH_SENSITIVE_CONTACTS("联系人"),
        HIGH_SENSITIVE_NOTIFICATIONS("通知正文"),
        HIGH_SENSITIVE_LOCATION("精确位置"),
        HIGH_SENSITIVE_FILE_TEXT("文件正文"),
        HIGH_SENSITIVE_BROWSER("浏览器页面内容"),
        HIGH_SENSITIVE_ACCESSIBILITY("Accessibility 内容"),
    }

    /** One piece of outgoing content. */
    sealed interface OutgoingContent {
        /** Text typed by the user in the chat input. */
        data object UserText : OutgoingContent

        /**
         * A file's textual body (HXA-049: chat attachments). Carries the materialized file's
         * display facts so the disclosure dialog can show 名称/类型/大小 (ADR-0014 §5): [sizeBytes]
         * is the full file size, [sha256] the hash of the full content, [kindLabel] a short
         * user-visible label of the first-batch text kind.
         */
        data class FileText(
            val sourceLabel: String,
            val sizeBytes: Long,
            val sha256: String,
            val kindLabel: String,
        ) : OutgoingContent
    }

    /**
     * One attachment's display facts in the [EgressSummary] (ADR-0014 §5: the dialog shows the
     * attachment 名称 / 类型 / 大小, in the same order the content sources list the files).
     */
    data class EgressAttachment(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val kindLabel: String,
    )

    /** The auditable summary shown in the disclosure dialog (doc 10 section 2.6). */
    data class EgressSummary(
        val providerId: String,
        val providerName: String,
        val protocol: ProviderProtocol,
        val origin: String,
        val residence: ProviderResidence,
        val categories: List<DataCategory>,
        val scope: String,
        val contentTruncated: Boolean,
        /** The send's attachments in content order (empty for a pure-text send). */
        val attachments: List<EgressAttachment> = emptyList(),
    ) {
        val hasHighSensitive: Boolean
            get() = categories.any { it != DataCategory.REGULAR }
    }

    /** The provider context of one outgoing send (display + residence facts). */
    data class EgressTarget(
        val providerId: String,
        val providerName: String,
        val protocol: ProviderProtocol,
        val origin: String,
        val residence: ProviderResidence,
    )

    /** The gate decision for one outgoing send. */
    sealed interface Decision {
        /** Regular content: send; the provider origin/residence stays visible in the chat header. */
        data object Proceed : Decision

        /** High-sensitivity content: per-send confirmation required (no permanent allow in STANDARD). */
        data class Confirm(
            val summary: EgressSummary,
        ) : Decision

        /** Forbidden content (credential shapes): rejected in BOTH profiles (ADR-0005). */
        data class Rejected(
            val reason: String,
        ) : Decision
    }

    /**
     * Decides the gate for one outgoing send. Order matters and is fixed:
     * 1. [ForbiddenContentGuard] runs FIRST (credential-shaped content is
     *    rejected in both profiles before any other classification);
     * 2. high-sensitivity categories force [Decision.Confirm] (both profiles
     *    in M2 — see the class KDoc for why Advanced does not bypass it yet);
     * 3. regular content proceeds (the origin/residence display is the
     *    standing visibility, FR-LLM-009).
     */
    fun decide(
        contents: List<OutgoingContent>,
        text: String,
        target: EgressTarget,
    ): Decision {
        ForbiddenContentGuard.reasonFor(text)?.let { return Decision.Rejected(it) }
        val categories = categoriesFor(contents)
        val summary =
            EgressSummary(
                providerId = target.providerId,
                providerName = target.providerName,
                protocol = target.protocol,
                origin = target.origin,
                residence = target.residence,
                categories = categories,
                scope = SCOPE_CURRENT_SESSION,
                contentTruncated = false,
                // ADR-0014 §5: the dialog shows every file's 名称/类型/大小 — in the same order
                // the content sources list them (empty for a pure-text send).
                attachments =
                    contents
                        .filterIsInstance<OutgoingContent.FileText>()
                        .map { EgressAttachment(it.sourceLabel, it.sizeBytes, it.sha256, it.kindLabel) },
            )
        return if (summary.hasHighSensitive) Decision.Confirm(summary) else Decision.Proceed
    }

    /** Maps outgoing content to data categories (pure; exhaustive over [OutgoingContent]). */
    fun categoriesFor(contents: List<OutgoingContent>): List<DataCategory> {
        val set = LinkedHashSet<DataCategory>()
        for (content in contents) {
            when (content) {
                OutgoingContent.UserText -> set += DataCategory.REGULAR
                is OutgoingContent.FileText -> set += DataCategory.HIGH_SENSITIVE_FILE_TEXT
            }
        }
        return if (set.isEmpty()) listOf(DataCategory.REGULAR) else set.toList()
    }

    /** The M2 scope label: chat content is scoped to the current session. */
    const val SCOPE_CURRENT_SESSION: String = "当前会话"

    /**
     * M2: NEITHER profile offers a permanent-allow option in the disclosure
     * dialog. STANDARD is bound by ADR-0005 permanently ("不提供永久允许");
     * ADVANCED gains its bounded, revocable rules (1h/24h/7d/30d) together
     * with the HXA-033 rule engine — until that milestone, M2 Advanced also
     * confirms per send, and the dialog says so explicitly instead of faking a
     * gated state. Kept as a named constant so the policy is assertable from
     * tests and the dialog renders from one place.
     */
    const val PERMANENT_ALLOW_OFFERED_IN_M2: Boolean = false
}

/**
 * Credential-shaped content detection (ADR-0005: "禁止发送内容包括 API key、OAuth
 * token、Cookie、密码、验证码、认证字段和其他凭据；两个配置都拒绝发送").
 *
 * The patterns are deliberately CONSERVATIVE and fixed: a hit blocks the send
 * with a user-visible reason (the matched content is never echoed). A false
 * positive is harmless (the user removes the secret and re-sends); a false
 * negative here is the dangerous direction, so new patterns are only added
 * with tests.
 */
object ForbiddenContentGuard {
    /** Returns the user-visible rejection reason, or null when the text is sendable. */
    fun reasonFor(text: String): String? {
        for (pattern in PATTERNS) {
            if (pattern.containsMatchIn(text)) return REJECTION_REASON
        }
        return null
    }

    private val PATTERNS: List<Regex> =
        listOf(
            // OpenAI / OpenAI-compatible family keys (sk-, incl. sk-ant- Anthropic).
            Regex("sk-[A-Za-z0-9_-]{16,}"),
            // AWS access key ids.
            Regex("AKIA[0-9A-Z]{16}"),
            // GitHub personal/access tokens (ghp/gho/ghu/ghs/ghr).
            Regex("gh[pousr]_[A-Za-z0-9]{16,}"),
            // Slack tokens.
            Regex("xox[baprs]-[A-Za-z0-9-]{8,}"),
            // PEM private key headers.
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            // Authorization bearer tokens (generic shape: >= 20 base64url chars).
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
            // JSON/YAML-style password fields with a non-placeholder value.
            Regex("(?i)\\bpassword\\b\\s*[:=]\\s*[\"']?[A-Za-z0-9!@#$%^&*()_+]{6,}"),
        )

    private const val REJECTION_REASON =
        "检测到凭据形态内容（API key / token / 密码 / 私钥），已拒绝发送；请移除后重试"
}
