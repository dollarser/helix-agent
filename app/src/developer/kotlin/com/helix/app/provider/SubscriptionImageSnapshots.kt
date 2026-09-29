package com.helix.app.provider

import com.helix.core.model.ModelRequest
import com.helix.provider.api.ProviderConfig
import com.helix.runtime.cli.client.CliImageSnapshot

/** Resolve every distinct message binding before crossing the subscription Runtime boundary. */
internal fun subscriptionImageSnapshots(
    request: ModelRequest,
    config: ProviderConfig,
    source: (() -> VisionImageSource)?,
): List<CliImageSnapshot> =
    request.messages.flatMap { it.images }.distinct().map { image ->
        val loaded = requireNotNull(source) { "Subscription image source unavailable" }.invoke().load(image, config)
        require(loaded.mediaType == image.mediaType) { "Subscription image media type changed" }
        CliImageSnapshot(image, loaded.base64)
    }
