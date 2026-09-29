package com.helix.core.model

/** Host-authored identity of an exposed binding. Never a permission or model-supplied argument. */
data class ToolBindingRef(
    val name: ToolName,
    val version: ToolVersion,
    val contractHash: String,
    val owner: String,
    val implementationRevision: String,
    /** Process-local incarnation; excluded from reusable approval identity. */
    val incarnation: String,
) {
    init {
        require(contractHash.length == 64 && contractHash.all { it in '0'..'9' || it in 'a'..'f' })
        require(owner.length in 1..1024)
        require(implementationRevision.length in 1..1024 && incarnation.length in 1..64)
    }
}

/** Exact model-facing name and its host binding; aliases are not a second resolution path. */
data class ExposedToolBinding(
    val exposedName: String,
    val binding: ToolBindingRef,
) {
    init {
        require(exposedName.length in 1..128)
    }
}
