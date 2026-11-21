package com.helix.extensions.skills

/** Request-local display facts only. Execution/read APIs never accept this snapshot as authority. */
data class SkillPresentationSnapshot(
    val installed: List<SkillListItem>,
    val session: List<SkillListItem>,
    val model: List<SkillListItem>,
    val documents: Map<SkillKey, SkillDocument>,
    val activated: List<ActivatedSkillContent>,
)

data class NativeSkillPresentation(
    val documents: List<SkillDocument>,
    val installedAvailable: Set<SkillKey>,
    val sessionAvailable: Set<SkillKey>,
)

/** Project only available native identities; other source eligibility remains with the repository. */
internal fun NativeSkillPresentation.projectItems(
    documents: Map<SkillKey, SkillDocument>,
    nativeKeys: Set<SkillKey>,
    session: String?,
    otherItem: (SkillKey, SkillDocument) -> SkillListItem?,
): List<SkillListItem> {
    val available = if (session == null) installedAvailable else sessionAvailable
    return documents
        .mapNotNull { (key, document) ->
            when {
                key !in nativeKeys -> otherItem(key, document)
                key in available -> nativeItem(key, document, true)
                else -> null
            }
        }.sortedWith(compareBy({ it.key.name }, { it.key.source.name }, { it.key.snapshotHash }))
}

internal fun nativeItem(
    key: SkillKey,
    document: SkillDocument,
    enabled: Boolean,
) = SkillListItem(
    key,
    document.catalogEntry.description,
    enabled,
    document.invocation,
    independentlySelectable = false,
    independentEnabled = false,
)

internal fun SkillDocument.selectedItem(
    key: SkillKey,
    selectable: Boolean,
    selection: Boolean?,
    ownerEnabled: Boolean,
): SkillListItem {
    val independent = selection ?: (key.source == SkillSource.BUILT_IN)
    return SkillListItem(
        key,
        catalogEntry.description,
        ownerEnabled || independent,
        invocation,
        independentlySelectable = selectable,
        independentEnabled = independent,
        explicitlyEnabled = ownerEnabled || selection == true,
    )
}
