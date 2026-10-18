package com.helix.app.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSourceIdentityTest {
    @Test fun normalizationDropsOnlyVersionAndNeverChangesOwnerOrRuntime() {
        assertEquals("plugin:mobile-use:mobile-use", stableNativeSourceRef("plugin:mobile-use:0.2.0:mobile-use"))
        assertEquals("plugin:other:runtime", stableNativeSourceRef("plugin:other:123:runtime"))
        assertEquals("plugin:mobile-use:mobile-use", stableNativeSourceRef("plugin:mobile-use:mobile-use"))
        assertEquals("mcp:server:version:hash", stableNativeSourceRef("mcp:server:version:hash"))
        assertEquals(
            "plugin:mobile-use:bad version:runtime",
            stableNativeSourceRef("plugin:mobile-use:bad version:runtime"),
        )
    }
}
