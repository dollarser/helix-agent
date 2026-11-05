package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillSource

/** Host labels never replace SkillKey identity or localize imported metadata by name. */
@Composable
internal fun skillCatalogPresentation(row: SkillListItem): Pair<String, String> {
    val labels = if (row.key.source == SkillSource.BUILT_IN) builtInSkillLabels(row.key.name) else null
    return if (labels == null) {
        row.key.name to row.description
    } else {
        stringResource(labels.first) to stringResource(labels.second)
    }
}

@Composable
internal fun skillCatalogName(
    source: String,
    name: String,
): String {
    val labels = if (source == SkillSource.BUILT_IN.name) builtInSkillLabels(name) else null
    return labels?.first?.let { stringResource(it) } ?: name
}

private fun builtInSkillLabels(name: String): Pair<Int, Int>? =
    when (name) {
        "helix-docs" -> {
            R.string.skill_catalog_helix_docs to R.string.skill_catalog_helix_docs_summary
        }

        "mcp-installer" -> {
            R.string.skill_catalog_mcp_installer to R.string.skill_catalog_mcp_installer_summary
        }

        "skill-installer" -> {
            R.string.skill_catalog_skill_installer to R.string.skill_catalog_skill_installer_summary
        }

        "skill-creator" -> {
            R.string.skill_catalog_skill_creator to R.string.skill_catalog_skill_creator_summary
        }

        "organize-files-preview" -> {
            R.string.skill_catalog_organize_files_preview to
                R.string.skill_catalog_organize_files_preview_summary
        }

        "web-research" -> {
            R.string.skill_catalog_web_research to R.string.skill_catalog_web_research_summary
        }

        "data-transform" -> {
            R.string.skill_catalog_data_transform to R.string.skill_catalog_data_transform_summary
        }

        "repo-inspection" -> {
            R.string.skill_catalog_repo_inspection to R.string.skill_catalog_repo_inspection_summary
        }

        "notification-digest" -> {
            R.string.skill_catalog_notification_digest to
                R.string.skill_catalog_notification_digest_summary
        }

        else -> {
            null
        }
    }
