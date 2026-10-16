package com.helix.app.plugin

import com.helix.extensions.plugin.PluginPackage
import java.util.UUID

/** Captured target revision: refresh must never silently authorize a newer installed version. */
data class PluginImportReview(
    val bundle: PluginPackage,
    val target: InstalledPlugin?,
    val candidates: List<InstalledPlugin>,
    val newIdentity: String,
    val update: PluginUpdateReport?,
) {
    val needsChoice: Boolean get() = target == null && candidates.isNotEmpty()
    val alreadyInstalled: Boolean get() = target?.hash == bundle.contentHash
    val identity: String get() = target?.identity ?: newIdentity

    fun chooseTarget(id: String): PluginImportReview {
        val selected = candidates.single { it.id == id }
        return copy(
            target = selected,
            candidates = emptyList(),
            update = PluginUpdateDiffEvaluator.evaluate(selected, bundle),
        )
    }

    fun chooseSeparate(): PluginImportReview = copy(target = null, candidates = emptyList(), update = null)

    companion object {
        fun capture(
            bundle: PluginPackage,
            installed: List<InstalledPlugin>,
            explicitTarget: InstalledPlugin? = null,
            uniqueId: () -> String = { UUID.randomUUID().toString() },
        ): PluginImportReview {
            require(explicitTarget?.native == null) { "PLUGIN_BUNDLED_UPDATED_WITH_APK" }
            val canonical = "local:${bundle.source}:${bundle.contentHash}"
            val local = installed.filter { it.native == null && it.identity.startsWith("local:") }
            val canonicalRecord = local.singleOrNull { it.identity == canonical }
            val requested =
                explicitTarget?.let { selected ->
                    val current = installed.singleOrNull { it.id == selected.id && it.identity == selected.identity }
                    requireNotNull(current) {
                        "CONNECTOR_VERSION_CHANGED"
                    }
                }
            val target = requested ?: canonicalRecord?.takeIf { it.hash == bundle.contentHash }
            val candidates =
                local.filter {
                    it.identity == canonical ||
                        (it.name == bundle.name && it.source == bundle.source)
                }
            return PluginImportReview(
                bundle,
                target,
                if (target == null) candidates else emptyList(),
                if (canonicalRecord == null) canonical else "$canonical:${uniqueId()}",
                target?.let { PluginUpdateDiffEvaluator.evaluate(it, bundle) },
            )
        }
    }
}
