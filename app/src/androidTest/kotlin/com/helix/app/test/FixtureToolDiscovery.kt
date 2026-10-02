package com.helix.app.test

import com.helix.app.tool.ToolPipeline

/** Exposure setup for fixtures testing another contract; never changes execution permission or binding checks. */
internal fun discoverFixtureTool(
    pipeline: ToolPipeline,
    sessionId: String,
    name: String,
) {
    val found = pipeline.mcpDiscovery.search(sessionId, name, 1)
    check(found.singleOrNull()?.name?.value == name) { "Fixture tool is not discoverable: $name" }
}
