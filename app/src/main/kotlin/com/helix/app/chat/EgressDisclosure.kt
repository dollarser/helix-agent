package com.helix.app.chat

import com.helix.app.R
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
 * HXA-069: the user-visible category labels, the current-session scope and the
 * attachment kind labels are emitted as STABLE string-resource IDs ([DataCategory.labelRes],
 * [EgressSummary.scope], [EgressAttachment.kindRes]) — never as locale text — so this pure,
 * plain-JUnit-tested object holds no [android.content.Context]. The Android UI (the disclosure
 * dialog) resolves them to the current locale via `stringResource`.
 *
 * What is NOT here (HXA-033, later milestone): stored Advanced rules with
 * 1h/24h/7d/30d expiry, clock-rewind fail-closed re-confirmation, and the
 * generic Policy engine. In M2 BOTH profiles confirm per send for
 * high-sensitivity content (STANDARD never offers permanent allow — and
 * neither does M2 Advanced, which says so honestly instead of faking a
 * "gated" state).
 */
object EgressDisclosure {
    /**
     * Data categories (ADR-0005 / doc 10 section 2.6). [labelRes] is the string-resource id of the
     * user-visible name (HXA-069).
     */
    enum class DataCategory(
        val labelRes: Int,
    ) {
        REGULAR(R.string.data_category_regular),
        HIGH_SENSITIVE_CONTACTS(R.string.data_category_contacts),
        HIGH_SENSITIVE_NOTIFICATIONS(R.string.data_category_notifications),
        HIGH_SENSITIVE_LOCATION(R.string.data_category_location),
        HIGH_SENSITIVE_FILE_TEXT(R.string.data_category_file_text),
        HIGH_SENSITIVE_IMAGE(R.string.data_category_image),
        HIGH_SENSITIVE_BROWSER(R.string.data_category_browser),
        HIGH_SENSITIVE_ACCESSIBILITY(R.string.data_category_accessibility),
    }

    /** One piece of outgoing content. */
    sealed interface OutgoingContent {
        /** Text typed by the user in the chat input. */
        data object UserText : OutgoingContent

        /**
         * A file's textual body (HXA-049: chat attachments). Carries the materialized file's
         * display facts so the disclosure dialog can show name / type / size (ADR-0014 §5):
         * [sizeBytes] is the full file size, [sha256] the hash of the full content, [kindRes]/
         * [kindArgs] a short localized label of the first-batch text kind (resolved by the UI,
         * HXA-069).
         */
        data class FileText(
            val sourceLabel: String,
            val sizeBytes: Long,
            val sha256: String,
            val kindRes: Int,
            val kindArgs: List<String> = emptyList(),
        ) : OutgoingContent

        /**
         * An attached image (HXA-055): the NORMALIZED artifact facts — [sizeBytes] and [sha256]
         * bind the re-encoded, EXIF-stripped bytes that leave the device; [mediaType] is the
         * closed wire type; [width]x[height] the normalized dimensions.
         */
        data class Image(
            val sourceLabel: String,
            val sizeBytes: Long,
            val sha256: String,
            val mediaType: String,
            val width: Int,
            val height: Int,
        ) : OutgoingContent
    }

    /**
     * One attachment's display facts in the [EgressSummary] (ADR-0014 §5: the dialog shows the
     * attachment name / type / size, in the same order the content sources list the files).
     * [kindRes]/[kindArgs] are the localized kind label (resolved by the UI, HXA-069).
     */
    data class EgressAttachment(
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val kindRes: Int,
        val kindArgs: List<String> = emptyList(),
    )

    /**
     * The auditable summary shown in the disclosure dialog (doc 10 section 2.6). [scope] is a
     * string-resource id (HXA-069), resolved by the dialog to the current locale.
     */
    data class EgressSummary(
        val providerId: String,
        val providerName: String,
        val protocol: ProviderProtocol,
        val origin: String,
        val residence: ProviderResidence,
        val categories: List<DataCategory>,
        val scope: Int,
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

        /**
         * Forbidden content (credential shapes): rejected in BOTH profiles (ADR-0005). [reason] is
         * a STABLE code (never locale text — resolved to a localized label by the Android caller,
         * HXA-069).
         */
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
                // ADR-0014 §5: the dialog shows every file's name/type/size — in the same order
                // the content sources list them (empty for a pure-text send). HXA-055: images
                // show their NORMALIZED size + bound hash (the bytes that leave the device).
                attachments =
                    contents.flatMap { content ->
                        when (content) {
                            is OutgoingContent.FileText -> {
                                listOf(
                                    EgressAttachment(
                                        content.sourceLabel,
                                        content.sizeBytes,
                                        content.sha256,
                                        content.kindRes,
                                        content.kindArgs,
                                    ),
                                )
                            }

                            is OutgoingContent.Image -> {
                                listOf(
                                    EgressAttachment(
                                        content.sourceLabel,
                                        content.sizeBytes,
                                        content.sha256,
                                        R.string.kind_image,
                                        listOf(content.mediaType, "${content.width}x${content.height}"),
                                    ),
                                )
                            }

                            else -> {
                                emptyList()
                            }
                        }
                    },
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
                is OutgoingContent.Image -> set += DataCategory.HIGH_SENSITIVE_IMAGE
            }
        }
        return if (set.isEmpty()) listOf(DataCategory.REGULAR) else set.toList()
    }

    /** The M2 scope (a string-resource id): chat content is scoped to the current session (HXA-069). */
    val SCOPE_CURRENT_SESSION: Int = R.string.scope_current_session

    /**
     * M2: NEITHER profile offers a permanent-allow option in the disclosure
     * dialog. STANDARD is bound by ADR-0005 permanently;
     * ADVANCED gains its bounded, revocable rules (1h/24h/7d/30d) together
     * with the HXA-033 rule engine — until that milestone, M2 Advanced also
     * confirms per send, and the dialog says so explicitly instead of faking a
     * gated state. Kept as a named constant so the policy is assertable from
     * tests and the dialog renders from one place.
     */
    const val PERMANENT_ALLOW_OFFERED_IN_M2: Boolean = false
}

/**
 * Credential-shaped content detection (ADR-0005: forbidden content — API key,
 * OAuth token, Cookie, password, verification code, auth fields and other
 * credentials; BOTH safety profiles refuse to send it).
 *
 * The patterns are deliberately CONSERVATIVE and fixed: a hit blocks the send
 * with a STABLE code (never locale text — HXA-069: the code is resolved to the
 * current locale by the Android-side caller) and the matched content is never
 * echoed. A false positive is harmless (the user removes the secret and
 * re-sends); a false negative here is the dangerous direction, so new patterns
 * are only added with tests.
 */
object ForbiddenContentGuard {
    /** The stable rejection code [reasonFor] returns on a hit (resolved to a localized label by the caller). */
    const val CREDENTIAL_DETECTED = "CREDENTIAL_DETECTED"

    /** Returns the stable rejection code, or null when the text is sendable. */
    fun reasonFor(text: String): String? {
        for (pattern in PATTERNS) {
            if (pattern.containsMatchIn(text)) return CREDENTIAL_DETECTED
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
}
