package com.helix.spike.termlib

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.helix.runtime.proot.app.ProotRuntimeInstaller
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import java.io.File
import java.security.MessageDigest

/** Reuses the production lock/parser/installer; no adb extraction or borrowed app state. */
object PtyRuntime {
    @JvmStatic fun prepare(context: Context): Array<String> {
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        val lock = ProotRuntimeInstaller.loadEmbeddedLock(context)
        if (RootFsInstaller.currentActive(root) == null) {
            val result =
                RootFsInstaller.install(
                    ProotRuntimeInstaller.buildInstallRequest(
                        context,
                        lock,
                        Os.sysconf(OsConstants._SC_PAGESIZE),
                        System.currentTimeMillis(),
                    ),
                )
            check(result is InstallOutcome.Success) { "Runtime installation failed: $result" }
        }
        val install = File(root, checkNotNull(RootFsInstaller.currentActive(root)).installId)
        val loader = File(context.applicationInfo.nativeLibraryDir, "libhelix_loader.so")

        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        check(loader.isFile && digest(loader) == digest(File(install, "bin/loader")))
        File(install, "pty-tmp").mkdirs()
        File(install, "pty-workspace").mkdirs()
        return arrayOf(install.absolutePath, loader.absolutePath)
    }
}
