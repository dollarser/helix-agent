package com.helix.app.proot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IsolatedProotFeasibilityDeviceTest {
    @Test
    fun reportIsolatedExecutionAndFileTransferBoundaries() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedProotProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "isolated-proot-probe").apply { mkdirs() }
        File(root, "input.txt").writeText("SYNTHETIC_INPUT")
        File(root, "tmp").mkdirs()
        val native = context.applicationInfo.nativeLibraryDir
        val host = ProcessBuilder("$native/libhelix_proot_probe.so", "-r", root.absolutePath, "-b", "/system", "-b", "/apex", "-b", "/dev", "-b", "/proc", "-w", "/", "/system/bin/sh", "-c", "cat /input.txt").redirectErrorStream(true)
        host.environment()["LD_LIBRARY_PATH"] = native
        host.environment()["PROOT_LOADER"] = "$native/libhelix_loader.so"
        host.environment()["PROOT_TMP_DIR"] = File(root, "tmp").absolutePath
        val child = host.start()
        val baseline = try {
            check(child.waitFor(10, TimeUnit.SECONDS))
            val output = child.inputStream.bufferedReader().readText()
            check(child.exitValue() == 0 && output.contains("SYNTHETIC_INPUT")) { output }
            output
        } finally { if (child.isAlive) child.destroyForcibly() }
        val reports = File(root, "report.txt").apply { writeText("hostUid=${Process.myUid()} hostProot=$baseline\n") }
        for (operation in listOf("fdInput", "reopenFileFd", "prootVersion", "prootJob", "directInput", "directoryFdInput", "rootfsWrite", "shell", "nativeExec", "nativeLinker", "nativeLoad", "network")) {
        val connected = CompletableFuture<IBinder>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val intent = Intent().setClassName(context.packageName, "com.helix.app.proot.IsolatedProotFeasibilityService")
        assertTrue(context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, operation, context.mainExecutor, connection))
        try {
            val binder = connected.get(20, TimeUnit.SECONDS)
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeString(operation)
                request.writeString(root.absolutePath)
                request.writeString(context.applicationInfo.nativeLibraryDir)
                ParcelFileDescriptor.open(File(root, "input.txt"), ParcelFileDescriptor.MODE_READ_ONLY).use {
                    it.writeToParcel(request, 0)
                    request.writeInt(if (operation == "directoryFdInput") 1 else 0)
                    if (operation == "directoryFdInput") {
                        val raw = Os.open(root.absolutePath, OsConstants.O_RDONLY, 0)
                        try {
                            ParcelFileDescriptor.dup(raw).use { dir ->
                                dir.writeToParcel(request, 0)
                                assertTrue(binder.transact(1, request, response, 0))
                            }
                        } finally { Os.close(raw) }
                    } else {
                        assertTrue(binder.transact(1, request, response, 0))
                    }
                }
                val report = requireNotNull(response.readString())
                reports.appendText("operation=$operation\n$report")
                assertTrue(report, report.contains("isolated=true"))
                if (operation == "fdInput") assertTrue(report, report.contains("fdInput=OK SYNTHETIC_INPUT"))
                // Report actual command/file failures; a passing probe is not B feasibility.
                if (operation == "network") assertTrue(report, report.contains("network=DENIED"))
            } catch (dead: android.os.DeadObjectException) {
                reports.appendText("operation=$operation TRANSACTION_FAILED: ${dead.message}\n")
                if (operation == "fdInput") throw dead
            } finally {
                request.recycle()
                response.recycle()
            }
        } finally { context.unbindService(connection) }
        }
    }
}
