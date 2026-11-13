package com.helix.app.search

import com.helix.core.agent.TurnContextRequest
import com.helix.provider.api.ProviderConfig
import com.helix.provider.openai.responses.OpenAiResponsesProvider

/** Request-time opt-in, after the existing session/mode/availability exposure filters. */
internal object NativeWebSearchAdmission {
    fun apply(
        request: TurnContextRequest,
        config: ProviderConfig,
        enabled: Boolean,
    ): TurnContextRequest {
        val admitted =
            enabled && OpenAiResponsesProvider.supportsNativeWebSearch(config) &&
                request.tools.any { it.name.value == "web_search" }
        return request.copy(
            nativeWebSearch = admitted,
            tools = if (admitted) request.tools.filterNot { it.name.value == "web_search" } else request.tools,
        )
    }
}
