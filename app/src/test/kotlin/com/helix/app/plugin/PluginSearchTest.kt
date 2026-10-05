package com.helix.app.plugin

import com.helix.extensions.plugin.PluginEndpoint
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSearchTest {
    @Test fun searchIncludesNativeAndPortableComponentsWithoutMatchingPrivateAddresses() {
        val portable =
            InstalledPlugin(
                "fixture",
                "Example",
                "local",
                "hash",
                listOf(InstalledEndpoint("endpoint", PluginEndpoint("Calendar", "https://private.invalid", false))),
                listOf(SkillKey(SkillSource.USER_IMPORTED, "Meeting notes", "hash")),
                emptyList(),
                "fixture",
                1,
                true,
            )
        val native = portable.copy(name = "mobile-use", native = NativePluginComponent("mobile-use", "fixture"))
        assertTrue(matchesPluginQuery(native, " Mobile "))
        assertTrue(matchesPluginQuery(native, "mobile-use"))
        assertTrue(matchesPluginQuery(native, "ui.snapshot", listOf("ui.snapshot")))
        assertTrue(matchesPluginQuery(portable, "CALENDAR"))
        assertTrue(matchesPluginQuery(portable, "meeting"))
        assertTrue(matchesPluginQuery(portable, "  "))
        assertFalse(matchesPluginQuery(portable, "private.invalid"))
        assertFalse(matchesPluginQuery(native, "no match"))
    }
}
