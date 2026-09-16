// Debug-only spike fixture: the package mirrors where the instrumented app code lived so the
// evidence doc can trace it; the file is never compiled by a module — detekt parsing only.
@file:Suppress("InvalidPackageDeclaration")

package com.helix.app.proot

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit

/** Debug-only feasibility instrumentation; never an Agent execution entry point. */
class IsolatedProotFeasibilityService : Service() {
    override fun onBind(intent: Intent): IBinder =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                val operation = requireNotNull(data.readString())
                val source = requireNotNull(data.readString())
                val native = requireNotNull(data.readString())
                val input = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                val directory = if (data.readInt() == 1) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
                val report =
                    StringBuilder("isolated=${Process.isIsolated()} uid=${Process.myUid()} pid=${Process.myPid()}\n")
                runProbes(operation, source, native, input, directory, report)
                requireNotNull(reply).writeString(report.toString())
                return true
            }
        }

    private fun runProbes(
        operation: String,
        source: String,
        native: String,
        input: ParcelFileDescriptor,
        directory: ParcelFileDescriptor?,
        report: StringBuilder,
    ) {
        fun attempt(name: String, action: () -> String) {
            if (name != operation) return
            android.util.Log.i("IsolatedProotProbe", "START $name uid=${Process.myUid()}")
            val result =
                runCatching(action).fold({ "OK $it" }, { "DENIED ${it.javaClass.simpleName}: ${it.message}" })
            report.append("$name=$result\n")
            android.util.Log.i("IsolatedProotProbe", "$name=$result")
        }
        try {
            attempt("network") {
                val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
                Os.close(fd)
                "socket created"
            }
            attempt("fdInput") {
                ParcelFileDescriptor.AutoCloseInputStream(input).bufferedReader().use { it.readText() }
            }
            attempt("reopenFileFd") { File("/proc/self/fd/${input.fd}").readText() }
            attempt("directInput") { File(source, "input.txt").readText() }
            attempt("directoryFdInput") {
                File("/proc/self/fd/${requireNotNull(directory).fd}/input.txt").readText()
            }
            attempt("rootfsWrite") {
                File(source, "isolated-output.txt").apply { writeText("synthetic") }.readText()
            }
            attempt("shell") { prootCommand(native, source, "/system/bin/sh", "-c", "printf ISOLATED_SHELL_OK") }
            attempt("nativeExec") { prootCommand(native, source, "$native/libhelix_isolated_probe.so") }
            attempt("nativeLinker") {
                prootCommand(native, source, "/system/bin/linker64", "$native/libhelix_isolated_probe.so")
            }
            attempt("prootVersion") { prootCommand(native, source, "$native/libhelix_proot_probe.so", "--version") }
            attempt("prootJob") {
                prootCommand(
                    native, source, "$native/libhelix_proot_probe.so", "-r", source,
                    "-b", "/system", "-b", "/apex", "-b", "/dev", "-b", "/proc", "-w", "/",
                    "/system/bin/sh", "-c", "cat /input.txt",
                )
            }
            attempt("nativeLoad") { System.load("$native/libproot_native.so"); "loaded" }
        } finally {
            input.close()
            directory?.close()
        }
    }

    private fun prootCommand(native: String, source: String, vararg args: String): String {
        val builder = ProcessBuilder(*args).redirectErrorStream(true)
        builder.environment()["LD_LIBRARY_PATH"] = native
        builder.environment()["PROOT_LOADER"] = "$native/libhelix_loader.so"
        builder.environment()["PROOT_TMP_DIR"] = "$source/tmp"
        val child = builder.start()
        try {
            check(child.waitFor(5, TimeUnit.SECONDS)) { "child timeout" }
            return "exit=${child.exitValue()} ${child.inputStream.bufferedReader().readText().take(2048)}"
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }
}
