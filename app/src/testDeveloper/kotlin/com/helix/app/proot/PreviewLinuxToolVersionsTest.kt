package com.helix.app.proot

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewLinuxToolVersionsTest {
    @Test fun declaredLinuxApisUseOneWhileJobFormatsKeepTheirOwnVersions() {
        val versions =
            listOf(
                LinuxRunTool.descriptor(),
                DetachedJobTools.start(),
                DetachedJobTools.control(false),
                DetachedJobTools.control(true),
                DetachedJobTools.collect(),
                DetachedJobTools.await(),
            ).associate { it.name.value to it.version.value }
        assertEquals(versions.mapValues { 1 }, versions)
        assertEquals(4, com.helix.runtime.proot.ipc.ProotRuntimeProtocol.PROTOCOL_VERSION)
        assertEquals(2, ProotJobBindingStore.PAYLOAD_VERSION)
    }
}
