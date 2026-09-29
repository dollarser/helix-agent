package com.helix.core.model

/** Only names actually sent in this request may resolve. This snapshot owns no executors. */
class ModelToolBindings(
    tools: List<ModelToolSchema>,
) {
    init {
        require(tools.size <= ModelRequest.MAX_TOOLS)
        require(tools.map { it.name }.distinct().size == tools.size) { "duplicate exposed tool name" }
    }

    private val byName = tools.associate { it.name.value to it.bindingRef }
    val entries: List<ExposedToolBinding> =
        byName.mapNotNull { (name, ref) ->
            ref?.let { ExposedToolBinding(name, it) }
        }

    fun resolve(exposedName: String): ToolBindingRef? = byName[exposedName]
}
