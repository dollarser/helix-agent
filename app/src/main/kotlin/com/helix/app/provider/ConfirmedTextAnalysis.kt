package com.helix.app.provider

import com.helix.app.chat.EgressDisclosure
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderProvisioningKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** An inert preview. Only the explicit confirmation callback invokes [execute]. */
class PreparedTextAnalysis internal constructor(
    val destination: EgressDisclosure.EgressTarget,
    val model: String,
    val input: String,
    val inputHash: String,
    internal val execute: suspend () -> String,
)

/** Tool-free, bounded text interpretation through the existing Provider/Egress path. */
class ConfirmedTextAnalysis(
    private val providers: ProviderService,
) {
    suspend fun prepare(
        title: String,
        system: String,
        input: String,
        providerId: String,
        model: String,
    ): PreparedTextAnalysis {
        require(input.isNotBlank() && input.length <= 6000)
        val config = providers.storedConfig(providerId)
        require(config.provisioning != ProviderProvisioningKind.MANAGED_ACCOUNT)
        require(
            providers.rows.value
                .first { it.id == providerId }
                .modelSelectable(model),
        )
        val target = providers.egressTargetFor(providerId)
        val hash = analysisHash(system + "\n" + input)
        val content =
            EgressDisclosure.OutgoingContent.ConversationReference(
                title,
                input.toByteArray().size.toLong(),
                hash,
            )
        require(EgressDisclosure.decide(listOf(content), input, target) !is EgressDisclosure.Decision.Rejected)
        return PreparedTextAnalysis(target, model, input, hash) {
            withContext(Dispatchers.IO) {
                check(providers.storedConfig(providerId) == config && providers.egressTargetFor(providerId) == target)
                check(
                    providers.rows.value
                        .first { it.id == providerId }
                        .modelSelectable(model),
                )
                val provider = providers.modelProviderFor(providerId, model)
                collectText(
                    provider.stream(
                        ModelRequest(
                            model,
                            messages =
                                listOf(
                                    ModelMessage(ModelRole.SYSTEM, system),
                                    ModelMessage(ModelRole.USER, input),
                                ),
                            maxOutputTokens = 2048,
                        ),
                    ),
                )
            }
        }
    }

    companion object {
        internal suspend fun collectText(events: Flow<ModelEvent>): String =
            withTimeout(90_000) {
                val body = StringBuilder()
                var complete = false
                events.collect { event ->
                    when (event) {
                        is ModelEvent.TextDelta -> {
                            check(!complete && body.length + event.text.length <= 8000)
                            body.append(event.text)
                        }

                        is ModelEvent.Completed -> {
                            check(!complete && event.finishReason in setOf(null, "stop", "end_turn"))
                            complete = true
                        }

                        is ModelEvent.Error, is ModelEvent.Refusal, is ModelEvent.ToolCallStarted,
                        is ModelEvent.ToolArgumentsDelta, is ModelEvent.ToolCallFinished,
                        -> {
                            error("interpretation-incomplete")
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                check(complete && body.isNotBlank())
                body.toString()
            }
    }
}

internal fun analysisHash(text: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
