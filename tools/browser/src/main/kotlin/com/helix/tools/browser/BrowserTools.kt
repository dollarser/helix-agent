package com.helix.tools.browser

import com.helix.tools.framework.ToolRegistry

object BrowserTools {
    /**
     * Registers all 12 `browser.*` contracts and registry against the shared [bridge].
     * Called once from the app container (which owns the production `BrowserToolBridgeImpl`);
     * tests build a [ToolRegistry] / [ToolRegistry] pair and a fake bridge.
     */
    fun registerAll(
        registry: ToolRegistry,
        bridge: BrowserToolBridge,
        visualPreparation: com.helix.tools.framework.ToolVisualPreparation? = null,
    ) {
        BrowserOpenTool.register(registry, bridge)
        BrowserNavigateTool.register(registry, bridge)
        BrowserBackTool.register(registry, bridge)
        BrowserForwardTool.register(registry, bridge)
        BrowserReloadTool.register(registry, bridge)
        BrowserSnapshotTool.register(registry, bridge)
        BrowserFindTool.register(registry, bridge)
        BrowserClickTool.register(registry, bridge)
        BrowserTypeTool.register(registry, bridge)
        BrowserScrollTool.register(registry, bridge)
        BrowserScreenshotTool.register(registry, bridge, visualPreparation)
        BrowserDownloadTool.register(registry, bridge)
    }
}
