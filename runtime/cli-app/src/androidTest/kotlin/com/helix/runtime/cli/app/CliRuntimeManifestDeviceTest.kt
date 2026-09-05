package com.helix.runtime.cli.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliRuntimeManifestDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun embeddedLockIsStrictAndContainsNoBundledExecutable() {
        val lock = CliEmbeddedBaseline.lock(context)
        assertEquals(setOf("node", "codex-app-server", "claude-code-npm"), lock.artifacts.map { it.id }.toSet())
        assertTrue(lock.artifacts.none { it.bundled })
        assertEquals(64, CliRuntimeLockCodec.sha256(lock).length)
    }

    @Test fun serviceIsExportedAndSignatureProtected() {
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, CliRuntimeService::class.java),
                PackageManager.GET_META_DATA,
            )
        assertTrue(info.exported)
        assertEquals(CliRuntimeProtocol.PERMISSION, info.permission)
        assertNotNull(context.packageManager.getPermissionInfo(CliRuntimeProtocol.PERMISSION, 0))
    }

    @Test fun permissionSurfaceIsNetworkOnly() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions?.toSet().orEmpty()
        assertEquals(setOf(Manifest.permission.INTERNET), requested)
        assertFalse(requested.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
        assertFalse(requested.contains(Manifest.permission.BIND_ACCESSIBILITY_SERVICE))
    }
}
