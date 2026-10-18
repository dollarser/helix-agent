package com.helix.app.tool

import com.helix.app.plan.PlanTools
import com.helix.runtime.quickjs.tool.CodeJavascriptRunTool
import com.helix.tools.browser.BrowserFindTool
import com.helix.tools.browser.BrowserScreenshotTool
import com.helix.tools.browser.BrowserSnapshotTool
import com.helix.tools.files.EditTool
import com.helix.tools.files.FilesCopyTool
import com.helix.tools.files.FilesDeleteTool
import com.helix.tools.files.FilesMkdirTool
import com.helix.tools.files.FilesMoveTool
import com.helix.tools.files.WriteTool
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewToolVersionsTest {
    @Test fun typedHostDeclarationConstantsUsePreviewVersionOne() {
        val versions =
            mapOf(
                EditTool.NAME to EditTool.VERSION,
                WriteTool.NAME to WriteTool.VERSION,
                FilesCopyTool.NAME to FilesCopyTool.VERSION,
                FilesDeleteTool.NAME to FilesDeleteTool.VERSION,
                FilesMkdirTool.NAME to FilesMkdirTool.VERSION,
                FilesMoveTool.NAME to FilesMoveTool.VERSION,
                BrowserSnapshotTool.NAME to BrowserSnapshotTool.VERSION,
                BrowserFindTool.NAME to BrowserFindTool.VERSION,
                BrowserScreenshotTool.NAME to BrowserScreenshotTool.VERSION,
                CodeJavascriptRunTool.NAME to CodeJavascriptRunTool.VERSION,
                PlanTools.NAME to PlanTools.VERSION,
            )
        assertEquals(versions.mapValues { 1 }, versions)
    }
}
