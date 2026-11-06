package com.helix.smoke

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Independent app UID; only rejected calls, never installs, clears data or sends automation input. */
class ComputerAdbBoundaryReleaseTest {
    @Test fun ordinaryApplicationCannotBeginOrAttachToTheInstalledAdvancedRelease() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val authority = "com.helix.agent.developer.adb-helper"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertEquals(0, provider.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertNotEquals(provider.applicationInfo.uid, Process.myUid())
        assertNotEquals(2000, Process.myUid())
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.readPermission)
        for (method in listOf("begin", "attach")) {
            assertThrows(SecurityException::class.java) {
                context.contentResolver.call(Uri.parse("content://$authority"), method, "a".repeat(32), Bundle())
            }
        }
    }
}
