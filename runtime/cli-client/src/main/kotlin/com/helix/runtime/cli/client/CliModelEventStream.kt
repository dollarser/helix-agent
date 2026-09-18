package com.helix.runtime.cli.client

import com.helix.core.model.ModelEvent
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Decode and verify before exposing any event. Keeps the event list, not a second whole JSON document. */
internal object CliModelEventStream {
    @OptIn(ExperimentalSerializationApi::class)
    fun readVerified(
        input: InputStream,
        expectedSha256: String,
    ): List<ModelEvent> {
        val digest = MessageDigest.getInstance("SHA-256")
        val envelope = DigestInputStream(input, digest).use { Json.decodeFromStream(EnvelopeDecoder, it) }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256) { "output hash mismatch" }
        require(envelope.version == 1)
        val events = envelope.events
        require(events.isNotEmpty())
        require(events.count(::isTerminal) == 1 && isTerminal(events.last())) { "model event terminal mismatch" }
        return events
    }

    private fun isTerminal(event: ModelEvent): Boolean =
        event is ModelEvent.Completed || event is ModelEvent.Refusal || event is ModelEvent.Error

    private data class Envelope(
        val version: Int,
        val events: List<ModelEvent>,
    )

    private object EnvelopeDecoder : DeserializationStrategy<Envelope> {
        private val eventsSerializer = ListSerializer(EventSerializer)
        override val descriptor =
            buildClassSerialDescriptor("CliModelEvents") {
                element<Int>("version")
                element("events", eventsSerializer.descriptor)
            }

        override fun deserialize(decoder: Decoder): Envelope =
            decoder.decodeStructure(descriptor) {
                var version: Int? = null
                var events: List<ModelEvent>? = null
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> break
                        0 -> version = decodeIntElement(descriptor, 0)
                        1 -> events = decodeSerializableElement(descriptor, 1, eventsSerializer)
                        else -> error("unexpected event envelope index: $index")
                    }
                }
                Envelope(requireNotNull(version), requireNotNull(events))
            }
    }

    private object EventSerializer : KSerializer<ModelEvent> {
        override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

        override fun deserialize(decoder: Decoder): ModelEvent =
            CliModelEventCodec.decodeEvent((decoder as JsonDecoder).decodeJsonElement())

        override fun serialize(
            encoder: Encoder,
            value: ModelEvent,
        ) {
            (encoder as JsonEncoder).encodeJsonElement(CliModelEventCodec.encodeEvent(value))
        }
    }
}
