package com.helix.app.plugin

data class PluginSessionRow(
    val id: String,
    val name: String,
    val selected: Boolean,
    val defaultSelected: Boolean,
    val available: Boolean,
    val ready: Boolean,
    val enabled: Boolean = true,
    val readyComponents: Int = 0,
    val totalComponents: Int = 0,
    val hasSkippedComponents: Boolean = false,
)
