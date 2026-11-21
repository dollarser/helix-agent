package com.helix.app.skills

import com.helix.extensions.plugin.PluginContentContribution
import com.helix.extensions.skills.SkillTools

/** Only the read schemas become direct; content identity and authority are revalidated at execution. */
internal fun directSkillToolNames(contributions: List<PluginContentContribution>): Set<String> =
    if (contributions.any {
            it is PluginContentContribution.ActivatedSkill ||
                (it is PluginContentContribution.SkillCatalog && it.selected)
        }
    ) {
        setOf(SkillTools.READ, SkillTools.READ_RESOURCE)
    } else {
        emptySet()
    }
