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
