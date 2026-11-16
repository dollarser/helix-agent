package com.helix.provider.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudModelPurposeTest {
    private fun purposes(fields: String) = ModelPurposes.outputModalities(Json.parseToJsonElement(fields).jsonObject)

    @Test fun cloudModalitiesDistinguishConversationRecognitionAndSynthesis() {
        assertEquals(setOf(ModelPurpose.CHAT), purposes("""{"architecture":{"output_modalities":["text"]}}"""))
        assertEquals(
            setOf(ModelPurpose.TRANSCRIPTION),
            purposes("""{"architecture":{"output_modalities":["transcription"]}}"""),
        )
        for (modality in listOf("audio", "speech")) {
            assertEquals(
                setOf(ModelPurpose.AUDIO),
                purposes("""{"architecture":{"output_modalities":["$modality"]}}"""),
            )
        }
    }

    @Test fun textProducedByTranscriptionIsNotChat() {
        assertEquals(
            setOf(ModelPurpose.TRANSCRIPTION),
            purposes("""{"architecture":{"output_modalities":["text"]},"endpoints":["/v1/audio/transcriptions"]}"""),
        )
        assertEquals(
            setOf(ModelPurpose.AUDIO),
            purposes("""{"architecture":{"output_modalities":["text","audio"]},"endpoints":["audio/speech"]}"""),
        )
    }

    @Test fun explicitChatEndpointPreservesMultimodalConversation() {
        assertEquals(
            setOf(ModelPurpose.CHAT, ModelPurpose.TRANSCRIPTION),
            purposes(
                """{"architecture":{"output_modalities":["text"]},
                    "endpoints":["responses","https://api.hilex.me/v1/audio/transcriptions"]}""",
            ),
        )
        assertEquals(
            setOf(ModelPurpose.CHAT, ModelPurpose.AUDIO),
            purposes("""{"architecture":{"output_modalities":["text","audio"]}}"""),
        )
    }

    @Test fun unknownIdsNeverInferChatOrAudio() {
        assertEquals(emptySet<ModelPurpose>(), purposes("""{"id":"gpt-transcribe"}"""))
        assertEquals(setOf(ModelPurpose.OTHER), purposes("""{"architecture":{"output_modalities":["unknown"]}}"""))
    }
}
