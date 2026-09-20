package com.helix.runtime.proot.app

import android.content.Context
import com.helix.runtime.proot.core.RootFsInstaller
import java.io.File
import java.security.MessageDigest

/** Fixed installed PRoot command. Real directory binding, no import copy, shell script or auto-install. */
internal class ProotTerminalLaunch(
    context: Context,
    workspacePath: String,
    sessionId: String,
) {
    val workspace: File
    private val argv: List<String>
    private val environment: Map<String, String>

    init {
        val allowed = File(context.filesDir, "workspaces/app").canonicalFile
        workspace = File(workspacePath).canonicalFile
        require(workspace.path == workspacePath && workspace.isDirectory)
        require(workspace == allowed || workspace.toPath().startsWith(allowed.toPath()))
        require(':' !in workspace.path && workspace.path.none(Char::isISOControl))
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        val active = checkNotNull(RootFsInstaller.currentActive(root)) { "Runtime requires preparation" }
        val install = File(root, active.installId)
        val loader = File(context.applicationInfo.nativeLibraryDir, "libhelix_loader.so")
        val digest = MessageDigest.getInstance("SHA-256")
        check(digest.digest(loader.readBytes()).contentEquals(digest.digest(File(install, "bin/loader").readBytes())))
        val temporary = File(install, "tmp/pty-$sessionId").apply { check(isDirectory || mkdirs()) }
        val home = File(install, "home").apply { check(isDirectory || mkdirs()) }
        argv =
            listOf(
                "/system/bin/linker64",
                File(install, "bin/proot").path,
                "--kill-on-exit",
                "-r",
                File(install, "rootfs").path,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "${workspace.path}:/workspace",
                "-b",
                "${temporary.path}:/tmp",
                "-b",
                "${home.path}:/root",
                "-w",
                "/workspace",
                "/bin/sh",
                "-i",
            )
        environment =
            mapOf(
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "TERM" to "xterm-256color",
                "HOME" to "/root",
                "PS1" to "helix> ",
                "LD_LIBRARY_PATH" to File(install, "bin/lib").path,
                "PROOT_LOADER" to loader.path,
                "PROOT_TMP_DIR" to temporary.path,
            )
    }

    fun spawn(): ProotPtyProcess = ProotPtyProcess.spawn(argv, environment)
}
