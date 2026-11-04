package com.helix.app.infrastructure.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Server prose expires in the diagnostic archive; stable execution/error facts remain in Room. */
internal fun durableModelFailurePayload(payload: String): String =
    JsonObject(Json.parseToJsonElement(payload).jsonObject.filterKeys { it != "serverMessage" }).toString()
