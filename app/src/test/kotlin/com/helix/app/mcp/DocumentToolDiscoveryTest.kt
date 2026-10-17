package com.helix.app.mcp

import com.helix.extensions.documents.DocumentTools
import com.helix.tools.framework.ToolOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentToolDiscoveryTest {
    @Test fun discoveringAnyDocumentOperationLoadsTheCompleteAvailableReadingGroup() {
        val all = listOf("inspect", "read", "view").map(DocumentTools::descriptor)
        for (matched in all) {
            assertEquals(
                DocumentTools.names,
                ToolDiscoveryWorkset.complete(listOf(matched), all).map { it.name.value }.toSet(),
            )
        }
        assertEquals(
            setOf("documents.read"),
            ToolDiscoveryWorkset
                .complete(listOf(all[1]), listOf(all[1]))
                .map {
                    it.name.value
                }.toSet(),
        )
    }

    @Test fun operationGroupsNeverBorrowAnotherPluginBinding() {
        val foreign =
            DocumentTools
                .descriptor(
                    "inspect",
                ).copy(origin = ToolOrigin.PluginOrigin("other", "0.1.0", "other"))
        assertEquals(
            listOf(foreign),
            ToolDiscoveryWorkset.complete(listOf(foreign), listOf(foreign, DocumentTools.descriptor("read"))),
        )
    }
}
