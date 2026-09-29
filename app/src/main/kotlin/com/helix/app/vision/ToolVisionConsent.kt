package com.helix.app.vision

import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.Clock
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.storage.repository.InteractionReceiptRepository
import com.helix.core.storage.repository.ReceiptResult
import com.helix.provider.api.ProviderConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
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
) {
    data class Pending(
        val id: String,
        val sessionId: String,
        val summary: EgressDisclosure.EgressSummary,
    )

    private val lock = Any()
    private val answers = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending

    suspend fun request(
        image: ImageReference,
        config: ProviderConfig,
        summary: EgressDisclosure.EgressSummary,
    ): String? {
        val binding = requireNotNull(image.binding)
        val id = identity(image, config)
        receipts.find(id)?.let { return id.takeIf { _ -> granted(image, config, id) } }
        val now = clock.now().toEpochMilli()
        val answer = CompletableDeferred<Boolean>()
        synchronized(lock) {
            check(id !in answers) { "Image disclosure is already pending" }
            receipts.open(
                InteractionReceiptRepository.ReceiptRequest(
                    id,
                    binding.sessionId,
                    requireNotNull(binding.turnId),
                    id,
                    1,
                    "Tool image data disclosure; image=${image.ref.value}; sha256=${binding.sha256}",
                    now,
                    WAIT_MILLIS,
                ),
            )
            answers[id] = answer
            _pending.value = _pending.value + Pending(id, binding.sessionId, summary)
        }
        try {
            val allowed = withTimeoutOrNull(WAIT_MILLIS) { answer.await() } ?: false
            val outcome = receipts.answer(id, decisionHash(id, allowed), clock.now().toEpochMilli())
            return id.takeIf { allowed && outcome is ReceiptResult.Answered }
        } finally {
            withContext(NonCancellable) {
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
        synchronized(lock) { answers[id]?.complete(allowed) }
    }

    fun granted(
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

        /** Exact content/destination identity, not a raw URL, secret or reusable permission. */
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
