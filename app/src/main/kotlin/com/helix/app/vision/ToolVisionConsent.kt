package com.helix.app.vision

import com.helix.app.R
import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.Clock
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.ProviderTransport
import com.helix.core.storage.repository.InteractionReceiptRepository
import com.helix.core.storage.repository.ReceiptResult
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

/** Data disclosure only: never creates a Tool Approval or changes a session permission preset. */
class ToolVisionConsent(
    private val receipts: InteractionReceiptRepository,
    private val clock: Clock,
    val mobileScreens: MobileUseScreenConsent = MobileUseScreenConsent(clock),
) {
    data class Pending(
        val id: String,
        val sessionId: String,
        val summary: EgressDisclosure.EgressSummary,
    )

    private class Answer {
        val choice = CompletableDeferred<Boolean>()
        val committed = CompletableDeferred<Boolean>()
    }

    private val lock = Any()
    private val answers = mutableMapOf<String, Answer>()
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending

    suspend fun request(
        image: ImageReference,
        config: ProviderConfig,
        summary: EgressDisclosure.EgressSummary,
    ): String? {
        val id = identity(image, config)
        receipts.find(id)?.takeUnless { it.state == "PENDING" }?.let {
            return id.takeIf { _ -> granted(image, config, id) }
        }
        val allowed = awaitDisclosure(id, requireNotNull(image.binding), summary, "Tool image data disclosure")
        return id.takeIf { allowed && granted(image, config, id) }
    }

    /** Native screenshots reuse the owning Conversation grant; this never prompts or changes it. */
    suspend fun requestMobileScreen(
        image: ImageReference,
        config: ProviderConfig,
    ): String? = mobileScreens.requestId(image, config)

    /** Ordinary images retain their existing content-bound disclosure workflow. */
    private suspend fun awaitDisclosure(
        id: String,
        binding: ImageBinding,
        summary: EgressDisclosure.EgressSummary,
        description: String,
    ): Boolean {
        var owner = false
        val answer =
            synchronized(lock) {
                answers[id] ?: Answer().also {
                    val now = clock.now().toEpochMilli()
                    if (receipts.find(id) == null) {
                        receipts.open(
                            InteractionReceiptRepository.ReceiptRequest(
                                id,
                                binding.sessionId,
                                requireNotNull(binding.turnId),
                                id,
                                1,
                                description,
                                now,
                                WAIT_MILLIS,
                            ),
                        )
                        answers[id] = it
                        _pending.value = _pending.value + Pending(id, binding.sessionId, summary)
                        owner = true
                    } else {
                        val receipt = receipts.find(id)
                        val allowed =
                            receipt?.sessionId == binding.sessionId && receipt.state == "ANSWERED" &&
                                receipt.requestId == id && receipt.createdAt <= now && now < receipt.expiresAt &&
                                receipt.answerHash == decisionHash(id, true)
                        it.committed.complete(allowed)
                    }
                }
            }
        if (!owner) return withTimeoutOrNull(WAIT_MILLIS) { answer.committed.await() } == true
        try {
            val allowed = withTimeoutOrNull(WAIT_MILLIS) { answer.choice.await() } == true
            val outcome = receipts.answer(id, decisionHash(id, allowed), clock.now().toEpochMilli())
            val committed = allowed && outcome is ReceiptResult.Answered
            answer.committed.complete(committed)
            return committed
        } finally {
            withContext(NonCancellable) {
                answer.committed.complete(false)
                try {
                    receipts.cancel(id)
                } finally {
                    synchronized(lock) {
                        answers.remove(id)
                        _pending.value = _pending.value.filterNot { it.id == id }
                    }
                }
            }
        }
    }

    /** Invoked only by the existing disclosure dialog's explicit user action. */
    fun respond(
        id: String,
        allowed: Boolean,
    ) {
        synchronized(lock) { answers[id]?.choice?.complete(allowed) }
    }

    fun granted(
        image: ImageReference,
        config: ProviderConfig,
        id: String,
    ): Boolean =
        if (id.startsWith(MobileUseScreenConsent.PREFIX)) {
            mobileScreens.granted(image, config, id)
        } else {
            imageGranted(image, config, id)
        }

    private fun imageGranted(
        image: ImageReference,
        config: ProviderConfig,
        id: String,
    ): Boolean {
        val binding = image.binding ?: return false
        val receipt = receipts.find(id)
        val now = clock.now().toEpochMilli()
        return receipt != null && id == identity(image, config) &&
            receipt.sessionId == binding.sessionId && receipt.turnId == binding.turnId &&
            receipt.requestId == id && receipt.state == "ANSWERED" &&
            receipt.createdAt <= now && now < receipt.expiresAt && receipt.answerHash == decisionHash(id, true)
    }

    companion object {
        private const val WAIT_MILLIS = 600_000L

        /** Exact content/destination identity for ordinary images; not a reusable screen permission. */
        fun identity(
            image: ImageReference,
            config: ProviderConfig,
        ): String {
            val binding: ImageBinding = requireNotNull(image.binding)
            val material =
                buildJsonArray {
                    add(binding.sessionId)
                    add(binding.turnId)
                    add(binding.messageId)
                    add(image.ref.value)
                    add(binding.sha256)
                    add(image.mediaType)
                    add(binding.modelId)
                    add(config.id)
                    add(config.connection.toString())
                    config.headers.toSortedMap().forEach { (key, value) ->
                        add(key)
                        add(value)
                    }
                }.toString()
            return "image-consent-" + digest(material)
        }

        private fun decisionHash(
            id: String,
            allowed: Boolean,
        ) = digest("$id:${if (allowed) "ALLOW" else "DENY"}")

        private fun digest(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
