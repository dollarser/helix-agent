package com.helix.app.localmodel

/** Pinned public sources for the small first-party install catalog. Display names are never asset identity. */
enum class LocalModelCatalogSource {
    MODELSCOPE,
    HUGGING_FACE,
}

enum class LocalModelCatalogEvidence {
    COMPATIBILITY_ONLY,
    FIXED_TASK_PASSED,
}

data class LocalModelCatalogLocation(
    val source: LocalModelCatalogSource,
    val repository: String,
    val revision: String,
    val downloadUrl: String,
    val redirectHostSuffixes: Set<String>,
)

data class LocalModelCatalogEntry(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val quantization: String,
    val license: String,
    val testedContextTokens: Int,
    val evidence: LocalModelCatalogEvidence,
    val locations: List<LocalModelCatalogLocation>,
) {
    fun location(source: LocalModelCatalogSource): LocalModelCatalogLocation =
        requireNotNull(locations.singleOrNull { it.source == source }) { "catalog source unavailable" }
}

object LocalModelCatalog {
    val entries: List<LocalModelCatalogEntry> =
        listOf(
            LocalModelCatalogEntry(
                id = "qwen3-0.6b-q4-k-m",
                displayName = "Qwen3 0.6B Q4_K_M",
                fileName = "Qwen3-0.6B-Q4_K_M.gguf",
                sha256 = "ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a",
                sizeBytes = 396_705_472,
                quantization = "Q4_K_M",
                license = "Apache-2.0",
                testedContextTokens = 8_192,
                evidence = LocalModelCatalogEvidence.COMPATIBILITY_ONLY,
                locations =
                    locations(
                        repository = "unsloth/Qwen3-0.6B-GGUF",
                        fileName = "Qwen3-0.6B-Q4_K_M.gguf",
                        modelScopeRevision = "6091bc857fe0dffa19c581a7ccc7def1b126ff54",
                        huggingFaceRevision = "f2d6f9ca53a254cc379437c49e4b2eb447f779df",
                    ),
            ),
            LocalModelCatalogEntry(
                id = "qwen3-4b-instruct-2507-q4-k-m",
                displayName = "Qwen3 4B Instruct 2507 Q4_K_M",
                fileName = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597",
                sizeBytes = 2_497_281_120,
                quantization = "Q4_K_M",
                license = "Apache-2.0",
                testedContextTokens = 8_192,
                evidence = LocalModelCatalogEvidence.FIXED_TASK_PASSED,
                locations =
                    locations(
                        repository = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                        fileName = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                        modelScopeRevision = "0b0406b39725d752255ffeb48f102c66f98e14aa",
                        huggingFaceRevision = "18727206c51467496bfba014368bd0a30e97f411",
                    ),
            ),
        )

    fun entry(id: String): LocalModelCatalogEntry =
        requireNotNull(entries.singleOrNull { it.id == id }) { "unknown curated local model" }

    private fun locations(
        repository: String,
        fileName: String,
        modelScopeRevision: String,
        huggingFaceRevision: String,
    ): List<LocalModelCatalogLocation> =
        listOf(
            location(LocalModelCatalogSource.MODELSCOPE, repository, fileName, modelScopeRevision),
            location(LocalModelCatalogSource.HUGGING_FACE, repository, fileName, huggingFaceRevision),
        )

    private fun location(
        source: LocalModelCatalogSource,
        repository: String,
        fileName: String,
        revision: String,
    ): LocalModelCatalogLocation {
        require(revision.matches(Regex("[a-f0-9]{40}")))
        val host = if (source == LocalModelCatalogSource.MODELSCOPE) "modelscope.cn/models" else "huggingface.co"
        return LocalModelCatalogLocation(
            source = source,
            repository = repository,
            revision = revision,
            downloadUrl = "https://$host/$repository/resolve/$revision/$fileName",
            redirectHostSuffixes =
                if (source == LocalModelCatalogSource.MODELSCOPE) {
                    setOf("modelscope.cn")
                } else {
                    setOf("huggingface.co", "hf.co")
                },
        )
    }
}
