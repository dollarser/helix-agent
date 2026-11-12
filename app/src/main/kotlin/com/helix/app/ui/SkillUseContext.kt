package com.helix.app.ui

import androidx.compose.runtime.saveable.listSaver
import com.helix.app.plugin.PluginService
import com.helix.app.plugin.PluginSetupReason
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource

internal val skillKeySaver =
    listSaver<SkillKey?, String>(
        save = { key -> key?.let { listOf(it.source.name, it.name, it.snapshotHash, it.ownerId) }.orEmpty() },
        restore = { if (it.isEmpty()) null else SkillKey(SkillSource.valueOf(it[0]), it[1], it[2], it[3]) },
    )

internal data class SkillUseContext(
    val initial: SkillKey? = null,
    val plugins: PluginService? = null,
    val configure: (String, PluginSetupReason?) -> Unit = { _, _ -> },
    val canApply: () -> Boolean = { true },
)

internal data class StandaloneSkillUseHost(
    val repository: com.helix.extensions.skills.SkillRepository?,
    val plugins: PluginService,
    val invoke: (suspend (String, com.helix.extensions.skills.UserSkillActivation) -> Boolean)?,
    val configure: (String, PluginSetupReason?) -> Unit,
)
