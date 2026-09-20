package com.helix.core.storage.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionExportReferencesTest {
    @Test fun omittedContentReferenceIsNotReportedAsAbsentHistory() {
        val data =
            Json
                .parseToJsonElement(
                    """{"sessionId":"session","turnId":null,"contentId":null,"omittedFields":{"contentRef":140000}}""",
                ).jsonObject
        val references =
            SessionExportReferences { true }
                .describe(SessionExportType.MESSAGE, data)
                .getValue("references")
                .jsonArray
        assertEquals(
            "omitted_limit",
            references
                .last()
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
    }

    @Test fun preservedCheckpointEdgesRemainExplicitWhenHistoryWasRemoved() {
        val projection =
            SessionExportReferences { it in setOf("message:checkpoint", "model_call:model", "message:kept") }
        val data =
            Json
                .parseToJsonElement(
                    """{"messageId":"checkpoint","contentId":null,"sourceCallId":"model",""" +
                        """"preservedMessageIds":["kept","gone"]}""",
                ).jsonObject
        val references = projection.describe(SessionExportType.COMPACTION, data).getValue("references").jsonArray
        assertEquals(
            listOf("included", "not_recorded", "included", "included", "not_in_selected_snapshot"),
            references.map {
                it.jsonObject
                    .getValue("status")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            "message:gone",
            references
                .last()
                .jsonObject
                .getValue("targetRecordId")
                .jsonPrimitive.content,
        )
    }

    @Test fun missingHistoricalModelAssociationIsNotInferredFromTurnOrCallOrder() {
        val projection = SessionExportReferences { true }
        val data = Json.parseToJsonElement("""{"turnId":"turn","modelCallId":null}""").jsonObject
        val references = projection.describe(SessionExportType.TOOL_CALL, data).getValue("references").jsonArray
        assertEquals(
            "not_recorded",
            references
                .last()
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
    }
}
