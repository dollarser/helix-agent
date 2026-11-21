package com.helix.extensions.plugin

import com.helix.extensions.skills.NativeSkillPresentation
import com.helix.extensions.skills.SkillDocument
import com.helix.extensions.skills.SkillKey

/** No live runtimes, leases or authority: immutable facts for one request's display projection. */
data class NativeContentSnapshot(
    val owner: NativePluginState,
    val ready: Boolean,
    val selected: Boolean,
    val selectionError: String?,
    val requiredInstructions: String,
    val documents: List<SkillDocument>,
)

fun List<NativeContentSnapshot>.skillPresentation(): NativeSkillPresentation =
    NativeSkillPresentation(
        flatMap { it.documents },
        filter { it.ready }.flatMap { it.documents }.map { it.presentationKey() }.toSet(),
        filter { it.ready && it.selected }.flatMap { it.documents }.map { it.presentationKey() }.toSet(),
    )

internal fun SkillDocument.presentationKey() =
    catalogEntry.let {
        SkillKey(it.source, it.name, it.contentHash, it.ownerId)
    }

/** Called only for enabled model-readable identities; ambient host help is discovery, not selection. */
internal fun com.helix.extensions.skills.SkillListItem.catalogContribution(
    document: SkillDocument,
): PluginContentContribution.SkillCatalog? {
    val hostHelp =
        key.source == com.helix.extensions.skills.SkillSource.BUILT_IN && key.ownerId.isEmpty() &&
            document.hostCatalogued
    return if (explicitlyEnabled ||
        hostHelp
    ) {
        PluginContentContribution.SkillCatalog(key, description, selected = explicitlyEnabled)
    } else {
        null
    }
}

fun presentationContributions(
    native: List<NativeContentSnapshot>,
    skills: com.helix.extensions.skills.SkillPresentationSnapshot,
): List<PluginContentContribution> =
    buildList {
        val owners = native.filter { it.ready && it.selected }
        val eligible =
            skills.model
                .filter { it.enabled }
                .map { it.key }
                .toSet()
        owners.forEach { entry ->
            entry.requiredInstructions.takeIf { it.isNotBlank() }?.let { text ->
                val hash =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(text.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                add(PluginContentContribution.RequiredInstructions(entry.owner, text, hash))
            }
        }
        skills.session.filter { it.enabled && it.key in eligible }.forEach { item ->
            val document = skills.documents.getValue(item.key)
            item.catalogContribution(document)?.let(::add)
        }
        owners.forEach { entry ->
            entry.documents.filter { it.presentationKey() in eligible }.forEach { document ->
                add(
                    PluginContentContribution.SkillCatalog(
                        document.presentationKey(),
                        document.catalogEntry.description,
                        entry.owner,
                    ),
                )
            }
        }
        skills.activated.distinctBy { it.key to it.arguments }.forEach { (key, document, arguments) ->
            add(
                PluginContentContribution.ActivatedSkill(
                    key,
                    document.body,
                    arguments,
                    owners.filter { it.documents.any { doc -> doc.presentationKey() == key } }.map { it.owner },
                ),
            )
        }
    }
