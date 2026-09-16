package com.helix.app.proot

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.cli.client.CliRuntimeConnection
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import com.helix.runtime.cli.client.CliRuntimeVerification
import com.helix.runtime.proot.app.ProotNative
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.client.ProotConnection
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import com.helix.runtime.proot.client.VerifiedRuntimeStore
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.ipc.ProotRuntimeAvailability
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Single installed developer APK, real Binder endpoints, no companion or account required. */
class IntegratedRuntimeDeviceTest {
    @Test
    fun initializeAndVerifyPrivateProcessesWithoutLegacyActivation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            pm.checkPermission("android.permission.INTERNET", context.packageName),
        )
        val legacy = File(context.filesDir, "proot-runtime/verified-runtime.json")
        assertFalse("Use an owned fresh emulator", legacy.exists())
        val legacyContent = writeValidLegacyAnchor(context, legacy)
        try {
            assertNull(VerifiedRuntimeStore(context).load())
            for ((service, suffix) in listOf(
                CliRuntimeProtocol.SERVICE_CLASS to ":subscriptions",
                ProotRuntimeProtocol.SERVICE_CLASS to ":proot",
            )) {
                val info = pm.getServiceInfo(ComponentName(context.packageName, service), 0)
                assertFalse(info.exported)
                assertEquals(context.packageName + suffix, info.processName)
                assertEquals(Process.myUid(), info.applicationInfo.uid)
            }
            installRuntime(context)
            CompletableFuture
                .runAsync {
                    val proot = ProotRuntimeSupervisor(context)
                    val cli = CliRuntimeSupervisor(context)
                    assertTrue(proot.verify(System.currentTimeMillis()) is ProotRuntimeAvailability.Verified)
                    assertTrue(cli.verify() is CliRuntimeVerification.Verified)
                    val prootConnection = proot.openConnection()
                    assertTrue(prootConnection is ProotConnection.Opened)
                    try {
                        val cliConnection = cli.openConnection()
                        assertTrue(cliConnection is CliRuntimeConnection.Opened)
                        try {
                            val processes = context.getSystemService(ActivityManager::class.java).runningAppProcesses
                            for (suffix in listOf(":subscriptions", ":proot")) {
                                val process = processes.single { it.processName == context.packageName + suffix }
                                assertEquals(Process.myUid(), process.uid)
                                assertNotEquals(Process.myPid(), process.pid)
                            }
                        } finally {
                            if (cliConnection is CliRuntimeConnection.Opened) cli.closeConnection(cliConnection)
                        }
                    } finally {
                        proot.closeConnection()
                    }
                }.get(90, TimeUnit.SECONDS)
            assertEquals(legacyContent, legacy.readText())
        } finally {
            legacy.delete()
        }
    }

    private fun installRuntime(context: Context) {
        val outcome =
            RootFsInstaller.install(
                ProotRuntimeInstaller.buildInstallRequest(
                    context,
                    ProotRuntimeInstaller.loadEmbeddedLock(context),
                    ProotNative.pageSizeBytes(),
                    System.currentTimeMillis(),
                ),
            )
        assertTrue("Actual embedded RootFS must install: $outcome", outcome is InstallOutcome.Success)
    }

    private fun writeValidLegacyAnchor(
        context: Context,
        file: File,
    ): String {
        val descriptor =
            com.helix.runtime.proot.ipc.RuntimeTargetDescriptorCodec.parse(
                com.helix.runtime.proot.app.ProotHandshakeManifest
                    .build(context)
                    .decodeToString(),
            )
        val legacy = VerifiedRuntimeStore(file)
        legacy.save(VerifiedRuntimeStore.Entry(descriptor, System.currentTimeMillis()))
        assertEquals(descriptor, legacy.load()!!.descriptor)
        return file.readText()
    }
}
