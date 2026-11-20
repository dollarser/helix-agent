package com.helix.app.plugin

import com.helix.tools.framework.ToolOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLabelsTest {
    @Test fun allBundledNamesShareBilingualAliasesAndKeepStableIdentities() {
        val cases =
            listOf(
                Triple("mobile-use", "Mobile Use", "手机操作"),
                Triple("browser", "Browser", "浏览器"),
                Triple("documents", "Documents", "文档"),
                Triple("chat-reply", "Chat Reply", "消息回复"),
            )
        cases.forEach { (id, english, chinese) ->
            val name = PluginName(id, id)
            assertEquals(english, name.displayName(listOf("en-US")))
            assertEquals(chinese, name.displayName(listOf("zh-CN")))
            assertEquals(chinese, name.displayName(listOf("fr", "zh-Hans-CN")))
            assertEquals(english, name.displayName(listOf("fr")))
            assertEquals(english, name.displayName(emptyList()))
            assertEquals(listOf(id, english, chinese), name.aliases)
            assertEquals(id, name.original)
            assertEquals(id, name.nativePluginId)
        }
    }

    @Test fun unknownAndThirdPartyNamesRemainAuthorSuppliedEvenWhenTheyCollide() {
        for (raw in listOf("browser", "mobile-use", "my_MCP-plugin", "手机操作", "iOS Tools")) {
            val name = PluginName(raw)
            for (locale in listOf("zh-CN", "en", "fr")) assertEquals(raw, name.displayName(listOf(locale)))
            assertEquals(listOf(raw), name.aliases)
        }
        assertEquals("Future Plugin", PluginName("Future Plugin", "future").displayName(listOf("zh-CN")))
    }

    @Test fun toolAliasesRequireTrustedMatchingNativeOriginNotNamesOrRemoteServerIds() {
        val native = ToolOrigin.PluginOrigin("mobile-use", "1", "mobile-use")
        assertTrue(requireNotNull(nativePluginName(native)).aliases.contains("手机操作"))
        assertEquals(listOf("mobile-use"), nativePluginName(native.copy(runtimeId = "other"))!!.aliases)
        assertNull(nativePluginName(ToolOrigin.BuiltInOrigin))
        assertNull(nativePluginName(ToolOrigin.McpOrigin("mobile-use", "1", "a".repeat(64))))
    }
}
