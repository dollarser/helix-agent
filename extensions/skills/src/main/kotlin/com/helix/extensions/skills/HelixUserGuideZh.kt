package com.helix.extensions.skills

/** Chinese reference content is packaged as resources, never UI literals. */
internal object HelixUserGuideZh {
    val resources: Map<String, String> =
        listOf(
            "references/index.zh-CN.md",
            "references/zh-CN/getting-started.md",
            "references/zh-CN/channels.md",
            "references/zh-CN/permissions.md",
            "references/zh-CN/mobile-use.md",
            "references/zh-CN/skills-connectors.md",
            "references/zh-CN/files-memory.md",
            "references/zh-CN/documents.md",
            "references/zh-CN/troubleshooting.md",
        ).associateWith { path ->
            val resource = "/helix-docs/" + path.removePrefix("references/")
            requireNotNull(HelixUserGuideZh::class.java.getResourceAsStream(resource)) {
                "Missing bundled help resource: $path"
            }.bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd() }
        }
}
