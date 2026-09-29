package com.helix.app.provider

import com.helix.app.internal.LineStore

/** User choices are not capability evidence and survive connection-test invalidation. */
class ProviderSelectedModels(
    private val store: LineStore,
) {
    fun read(id: String): List<String> = store.lines("provider-selected-models-$id")

    fun write(
        id: String,
        models: List<String>,
    ) {
        validate(models)
        store.setLines("provider-selected-models-$id", models.distinct())
    }

    companion object {
        fun validate(models: List<String>) {
            require(models.size <= 1024)
            require(
                models.all { model ->
                    model.isNotBlank() && model.length <= 256 && model.none { it <= ' ' || it == '\u007f' }
                },
            )
        }
    }
}
