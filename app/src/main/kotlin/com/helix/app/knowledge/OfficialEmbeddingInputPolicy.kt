package com.helix.app.knowledge

/** Gateway-specific reservation bounds, not OpenAI-wide or local embedding model limits. */
internal fun validateOfficialEmbeddingInputs(
    model: String,
    inputs: List<String>,
) {
    if (model != "text-embedding-3-small") return
    require(inputs.size in 1..32) { "Cloud embedding input count exceeds the gateway limit" }
    val sizes = inputs.map { it.toByteArray(Charsets.UTF_8).size }
    require(inputs.all { it.isNotBlank() } && sizes.all { it <= 8192 }) {
        "Cloud embedding input exceeds the UTF-8 byte limit"
    }
    require(sizes.sumOf { it.toLong() } <= 32768L) { "Cloud embedding batch exceeds the UTF-8 byte limit" }
}
