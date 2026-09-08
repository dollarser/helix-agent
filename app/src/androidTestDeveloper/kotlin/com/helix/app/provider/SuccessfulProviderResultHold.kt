package com.helix.app.provider

/** Holds a real persisted result before Chat receives its first event; test APK only. */
@Suppress("UNCHECKED_CAST")
internal fun holdSuccessfulProviderResult(
    container: com.helix.app.AppContainer,
    resultHeld: java.util.concurrent.atomic.AtomicBoolean,
) {
    val field =
        container.providerService.javaClass
            .getDeclaredField("factory")
            .apply { isAccessible = true }
    val factory = field.get(container.providerService) as ProviderFactory
    val extra = factory.javaClass.getDeclaredField("additionalFactory").apply { isAccessible = true }
    val original =
        extra.get(factory) as (
            com.helix.provider.api.ProviderConfig,
        ) -> com.helix.provider.api.ModelProvider?
    val wrapped: (com.helix.provider.api.ProviderConfig) -> com.helix.provider.api.ModelProvider? = { config ->
        original(config)?.let { delegate ->
            object : com.helix.provider.api.ModelProvider by delegate {
                override fun stream(request: com.helix.core.model.ModelRequest) =
                    kotlinx.coroutines.flow.flow {
                        delegate.stream(request).collect { event ->
                            if (resultHeld.compareAndSet(false, true)) kotlinx.coroutines.awaitCancellation()
                            emit(event)
                        }
                    }
            }
        }
    }
    extra.set(factory, wrapped)
}
