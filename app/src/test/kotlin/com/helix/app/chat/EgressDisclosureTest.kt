package com.helix.app.chat

import com.helix.app.R
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EgressDisclosureTest {
    private val target =
        EgressDisclosure.EgressTarget(
            providerId = "prov_1",
            providerName = "OpenAI",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            origin = "https://api.openai.com",
            residence = ProviderResidence.PUBLIC_CLOUD,
        )

    @Test
    fun userTextAloneIsRegularAndProceeds() {
        val decision = EgressDisclosure.decide(listOf(EgressDisclosure.OutgoingContent.UserText), "你好", target)
        assertTrue(decision is EgressDisclosure.Decision.Proceed)
    }

    @Test
    fun fileTextContentRequiresPerSendConfirmationWithFullSummary() {
        val decision =
            EgressDisclosure.decide(listOf(fileText("note.txt", 12L, R.string.kind_txt)), "看看这个文件", target)
                as EgressDisclosure.Decision.Confirm
        val summary = decision.summary
        assertEquals(listOf(EgressDisclosure.DataCategory.HIGH_SENSITIVE_FILE_TEXT), summary.categories)
        assertEquals("prov_1", summary.providerId)
        assertEquals("https://api.openai.com", summary.origin)
        assertEquals(ProviderResidence.PUBLIC_CLOUD, summary.residence)
        assertEquals(EgressDisclosure.SCOPE_CURRENT_SESSION, summary.scope)
        // ADR-0014 §5: the disclosure shows the file's 名称/类型/大小.
        assertEquals(
            listOf(EgressDisclosure.EgressAttachment("note.txt", 12L, SHA, R.string.kind_txt)),
            summary.attachments,
        )
    }

    @Test
    fun fileTextFactsAreCarriedIntoTheSummaryAttachmentsInContentOrder() {
        val decision =
            EgressDisclosure.decide(
                listOf(
                    EgressDisclosure.OutgoingContent.UserText,
                    fileText("a.txt", 10L, R.string.kind_txt),
                    fileText("b.md", 2205L, R.string.kind_markdown),
                ),
                "看看",
                target,
            ) as EgressDisclosure.Decision.Confirm
        assertEquals(
            listOf(
                EgressDisclosure.EgressAttachment("a.txt", 10L, SHA, R.string.kind_txt),
                EgressDisclosure.EgressAttachment("b.md", 2205L, SHA, R.string.kind_markdown),
            ),
            decision.summary.attachments,
        )
    }

    @Test
    fun aPureTextSendHasNoAttachmentsInTheSummary() {
        // No regression: a send with no FileText source never carries attachment facts — the
        // summary's attachments default to empty.
        val summary =
            EgressDisclosure.EgressSummary(
                providerId = "prov_1",
                providerName = "OpenAI",
                protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                origin = "https://api.openai.com",
                residence = ProviderResidence.PUBLIC_CLOUD,
                categories = listOf(EgressDisclosure.DataCategory.REGULAR),
                scope = EgressDisclosure.SCOPE_CURRENT_SESSION,
                contentTruncated = false,
            )
        assertTrue(summary.attachments.isEmpty())
    }

    @Test
    fun anImageRequiresConfirmationAndDisclosesTheNormalizedFacts() {
        // HXA-055 (ADR-0014 §5): the image's disclosure row carries the NORMALIZED size and the
        // BOUND (normalized) hash — the exact bytes that leave the device — plus type and
        // normalized dimensions, under the image data category.
        val decision =
            EgressDisclosure.decide(
                listOf(EgressDisclosure.OutgoingContent.UserText, image("photo.jpg", 123_456L)),
                "看这张",
                target,
            ) as EgressDisclosure.Decision.Confirm
        assertEquals(
            listOf(EgressDisclosure.DataCategory.REGULAR, EgressDisclosure.DataCategory.HIGH_SENSITIVE_IMAGE),
            decision.summary.categories,
        )
        assertEquals(
            listOf(
                EgressDisclosure.EgressAttachment(
                    "photo.jpg",
                    123_456L,
                    SHA,
                    R.string.kind_image,
                    listOf("image/jpeg", "1024x768"),
                ),
            ),
            decision.summary.attachments,
        )
    }

    @Test
    fun imageAndTextAttachmentsKeepContentOrderInTheDisclosure() {
        val decision =
            EgressDisclosure.decide(
                listOf(
                    image("a.jpg", 10L),
                    fileText("b.md", 22L, R.string.kind_markdown),
                ),
                "",
                target,
            ) as EgressDisclosure.Decision.Confirm
        assertEquals(2, decision.summary.attachments.size)
        assertEquals("a.jpg", decision.summary.attachments[0].fileName)
        assertEquals("b.md", decision.summary.attachments[1].fileName)
        assertEquals(
            listOf(
                EgressDisclosure.DataCategory.HIGH_SENSITIVE_IMAGE,
                EgressDisclosure.DataCategory.HIGH_SENSITIVE_FILE_TEXT,
            ),
            decision.summary.categories,
        )
    }

    private fun fileText(
        label: String,
        sizeBytes: Long,
        kindRes: Int,
    ) = EgressDisclosure.OutgoingContent.FileText(label, sizeBytes, SHA, kindRes)

    private fun image(
        label: String,
        sizeBytes: Long,
    ) = EgressDisclosure.OutgoingContent.Image(label, sizeBytes, SHA, "image/jpeg", 1024, 768)

    @Test
    fun mixedContentSurfacesEveryCategory() {
        val categories =
            EgressDisclosure.categoriesFor(
                listOf(
                    EgressDisclosure.OutgoingContent.UserText,
                    fileText("a", 1L, R.string.kind_txt),
                ),
            )
        assertEquals(
            listOf(
                EgressDisclosure.DataCategory.REGULAR,
                EgressDisclosure.DataCategory.HIGH_SENSITIVE_FILE_TEXT,
            ),
            categories,
        )
    }

    @Test
    fun noPermanentAllowInM2ForEitherProfile() {
        // M2 contract (ADR-0005 STANDARD 不提供永久允许; Advanced rule engine is
        // HXA-033 — M2 Advanced confirms per send and says so honestly).
        assertEquals(false, EgressDisclosure.PERMANENT_ALLOW_OFFERED_IN_M2)
    }

    @Test
    fun credentialShapesAreRejectedBeforeAnyOtherClassification() {
        // Fixture length discipline: every literal below sits at or above the
        // ForbiddenContentGuard's pattern minimums (the rejection must be
        // exercised) but strictly BELOW the repo secret-scan gate's thresholds
        // (check-secrets.sh: sk-/ghp_/xoxb- need 20+ chars) so the fixtures can
        // never be mistaken for real credentials by the gate. The AKIA and
        // PRIVATE KEY shapes have IDENTICAL guard/scan patterns, so they are
        // assembled from fragments at runtime — the source text never contains
        // a complete credential-shaped literal.
        for (text in listOf(
            "key is sk-abcdefghijklmnopq",
            "aws ${AKIA_FIXTURE}",
            "token ghp_ABCDEFGHIJKLMNOP",
            "slack xoxb-123456789012-abc",
            PRIVATE_KEY_FIXTURE,
            "Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345",
            "password: SuperSecret123",
        )) {
            val decision =
                EgressDisclosure.decide(listOf(EgressDisclosure.OutgoingContent.UserText), text, target)
            assertTrue("expected rejection for: $text", decision is EgressDisclosure.Decision.Rejected)
        }
    }

    @Test
    fun rejectionNeverEchoesTheMatchedContent() {
        val secret = "sk-abcdefghijklmnopq"
        val decision =
            EgressDisclosure.decide(listOf(EgressDisclosure.OutgoingContent.UserText), "use $secret now", target)
                as EgressDisclosure.Decision.Rejected
        assertTrue(secret !in decision.reason)
    }

    @Test
    fun ordinaryTextPassesTheGuard() {
        assertNull(ForbiddenContentGuard.reasonFor("今天天气不错，帮我总结一下"))
        assertNull(ForbiddenContentGuard.reasonFor("password 字段是空的"))
    }

    private companion object {
        // The disclosure only displays the hash facts; a fixed 64-hex value is enough.
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /** "AKIA" + 16 uppercase alphanumerics; assembled from fragments (see the fixture comment above). */
        const val AKIA_FIXTURE = "AKIA" + "IOSFODNN7EXAMPLE"

        /** An RSA private-key header; assembled from fragments (see the fixture comment above). */
        const val PRIVATE_KEY_FIXTURE = "-----BEGIN " + "RSA " + "PRIVATE KEY-----"
    }
}
