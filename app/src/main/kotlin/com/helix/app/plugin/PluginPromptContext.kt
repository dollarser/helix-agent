package com.helix.app.plugin

import com.helix.extensions.plugin.PluginContentContribution

/** One request's presentation facts, never passed to execution admission. */
data class PluginPromptContext(
    val contributions: List<PluginContentContribution>,
    val readiness: PluginReadinessContext,
)
