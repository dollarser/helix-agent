package com.helix.tools.browser

import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry

object BrowserTools {
    /**
     * Registers all 12 `browser.*` contracts and implementations against the shared [bridge].
     * Called once from the app container (which owns the production `BrowserToolBridgeImpl`);
     * tests build a [ToolRegistry] / [ToolImplementationRegistry] pair and a fake bridge.
     */
    fun registerAll(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: BrowserToolBridge,
    ) {
        BrowserOpenTool.register(registry, implementations, bridge)
        BrowserNavigateTool.register(registry, implementations, bridge)
        BrowserBackTool.register(registry, implementations, bridge)
        BrowserForwardTool.register(registry, implementations, bridge)
        BrowserReloadTool.register(registry, implementations, bridge)
        BrowserSnapshotTool.register(registry, implementations, bridge)
        BrowserFindTool.register(registry, implementations, bridge)
        BrowserClickTool.register(registry, implementations, bridge)
        BrowserTypeTool.register(registry, implementations, bridge)
        BrowserScrollTool.register(registry, implementations, bridge)
        BrowserScreenshotTool.register(registry, implementations, bridge)
        BrowserDownloadTool.register(registry, implementations, bridge)
    }
}
