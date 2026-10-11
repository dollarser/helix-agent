package com.helix.tools.browser

import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolVisualPreparation

object BrowserTools {
    /** The host-native Browser contribution owns publication; executors keep their existing bridge. */
    fun bindings(
        bridge: BrowserToolBridge,
        origin: ToolOrigin.PluginOrigin,
        visualPreparation: ToolVisualPreparation? = null,
    ): List<ToolBinding> =
        listOf(
            ToolBinding(BrowserTabsTool.descriptor(origin), BrowserTabsTool.executor(bridge)),
            ToolBinding(BrowserWaitTool.descriptor(origin), BrowserWaitTool.executor(bridge)),
            ToolBinding(BrowserOpenTool.descriptor(origin), BrowserOpenTool.executor(bridge)),
            ToolBinding(BrowserNavigateTool.descriptor(origin), BrowserNavigateTool.executor(bridge)),
            ToolBinding(BrowserBackTool.descriptor(origin), BrowserBackTool.executor(bridge)),
            ToolBinding(BrowserForwardTool.descriptor(origin), BrowserForwardTool.executor(bridge)),
            ToolBinding(BrowserReloadTool.descriptor(origin), BrowserReloadTool.executor(bridge)),
            ToolBinding(BrowserSnapshotTool.descriptor(origin), BrowserSnapshotTool.executor(bridge)),
            ToolBinding(BrowserFindTool.descriptor(origin), BrowserFindTool.executor(bridge)),
            ToolBinding(BrowserClickTool.descriptor(origin), BrowserClickTool.executor(bridge)),
            ToolBinding(BrowserTypeTool.descriptor(origin), BrowserTypeTool.executor(bridge)),
            ToolBinding(BrowserScrollTool.descriptor(origin), BrowserScrollTool.executor(bridge)),
            ToolBinding(
                BrowserScreenshotTool.descriptor(origin),
                BrowserScreenshotTool.executor(bridge, visualPreparation),
            ),
            ToolBinding(BrowserDownloadTool.descriptor(origin), BrowserDownloadTool.executor(bridge)),
        )
}
