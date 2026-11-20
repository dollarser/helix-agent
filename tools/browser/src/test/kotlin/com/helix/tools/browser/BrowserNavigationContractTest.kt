package com.helix.tools.browser

import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationContractTest {
    private val origin = ToolOrigin.PluginOrigin("browser", "0.1.0", "browser")

    @Test
    fun navigationDirectsTheModelToObserveRatherThanAssumePageLoad() {
        val open = BrowserOpenTool.descriptor(origin)
        val navigate = BrowserNavigateTool.descriptor(origin)
        assertTrue(open.description.contains("browser_navigate"))
        for (descriptor in listOf(open, navigate)) {
            assertTrue(descriptor.description.contains("browser_snapshot"))
            val url =
                descriptor.outputSchema!!
                    .getValue("properties")
                    .jsonObject
                    .getValue("url")
                    .jsonObject
            assertTrue(
                url
                    .getValue("description")
                    .jsonPrimitive.content
                    .contains("not proof"),
            )
        }
    }

    @Test
    fun browserSearchExplainsHowToReadResultsAndItsApiAlternative() {
        val descriptor = BrowserSearchTool.descriptor(origin)
        assertTrue(descriptor.description.contains("web_search"))
        assertTrue(descriptor.description.contains("browser_snapshot"))
        assertTrue(descriptor.description.contains("NOT search results"))
    }
}
