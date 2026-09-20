package com.helix.runtime.proot.app

import android.content.Context
import android.os.SystemClock
import android.system.OsConstants
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.PtyInputConnection
import com.helix.runtime.proot.core.PtyOutputBuffer
import com.helix.runtime.proot.core.RootFsInstaller
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Known synthetic input only; the production primitive runs in the service's private Runtime PID. */
internal class PtyNativeJourney(
    private val context: Context,
) {
    private fun startInteractive(): ProotPtyProcess {
        val (install, loader) = prepare()
        val workspace = File(install, "native-pty-workspace").apply { mkdirs() }
        val temporary = File(install, "native-pty-tmp").apply { mkdirs() }
        val args =
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
                "-w",
                "/workspace",
                "/bin/sh",
                "-i",
            )
        val environment =
            mapOf(
                "PATH" to "/usr/local/bin:/usr/bin:/bin",
                "TERM" to "xterm-256color",
                "NATIVE_UTF8" to "变量🙂",
                "PS1" to PROMPT,
                "LD_LIBRARY_PATH" to File(install, "bin/lib").path,
                "PROOT_LOADER" to loader.path,
                "PROOT_TMP_DIR" to temporary.path,
            )
        return ProotPtyProcess.spawn(args, environment)
    }

    fun interactive() {
        usePtyProcess(startInteractive()) { process ->
            val channel = Channel(process)
            channel.until(PROMPT)
            channel.exchange("test -t 0 && test -t 1 && printf 'TTY_%s\\n' YES\n", "TTY_YES")
            channel.exchange("printf 'ENV_%s\\n' \"\$NATIVE_UTF8\"\n", "ENV_变量🙂")
            channel.exchange("stty size\n", "24 80")
            process.resize(37, 101)
            channel.exchange("stty size\n", "37 101")
            channel.exchange("export PTY_VALUE='保留🙂'; cd /tmp; printf 'SET_%s\\n' YES\n", "SET_YES")
            channel.reconnect()
            channel.exchange("printf 'STATE_%s_%s\\n' \"\$PTY_VALUE\" \"\$PWD\"\n", "STATE_保留🙂_/tmp")
            val shell = process.foregroundGroup()
            channel.send("sleep 30\n".toByteArray())
            awaitPty { process.foregroundGroup() != shell }
            val foreground = process.foregroundGroup()
            channel.send(byteArrayOf(3))
            channel.until(PROMPT)
            channel.exchange("printf 'EXIT_%s\\n' \"\$?\"\n", "EXIT_130")
            awaitPty { !ptyExists(foreground) }
            channel.exchange("python3 -q\n", "", ">>> ")
            channel.exchange("value=6*7; print('NATIVE_%s_中文🙂' % value)\n", "NATIVE_42_中文🙂", ">>> ")
            channel.send(byteArrayOf(4))
            channel.until(PROMPT)
            channel.send(byteArrayOf(4))
            awaitPty { process.pollExit() != null }
            check(process.pollExit() == 0)
        }
    }

    fun prootClosure(quit: Boolean) {
        usePtyProcess(
            ProotPtyProcess.spawn(listOf("/system/bin/sh", "-c", "exec /system/bin/sleep 120"), emptyMap()),
        ) { sentinel ->
            usePtyProcess(startInteractive()) { process ->
                val channel = Channel(process)
                channel.until(PROMPT)
                val shellGroup = process.foregroundGroup()
                channel.send(
                    (
                        "rm -f /tmp/pty-detached.pid; sleep 30 & bg=\$!; " +
                            "python3 -c \"import os,time; os._exit(0) if os.fork() else None; os.setsid(); " +
                            "open('/tmp/pty-detached.pid','w').write(str(os.getpid())); time.sleep(30)\" & " +
                            "while [ ! -s /tmp/pty-detached.pid ]; do sleep 0.01; done; " +
                            "printf 'JOBS_%s_%s\\n' \"\$bg\" \"\$(cat /tmp/pty-detached.pid)\"\n"
                    ).toByteArray(),
                )
                val response = channel.until("JOBS_", PROMPT)
                val match = checkNotNull(Regex("JOBS_([0-9]+)_([0-9]+)").find(response))
                val background = match.groupValues[1].toInt()
                val detached = match.groupValues[2].toInt()
                check(ptyExists(background) && ptyExists(detached))
                check(ptyProcessFields(background)[2].toInt() != shellGroup)
                check(ptyProcessFields(detached)[3].toInt() == detached)
                var foreground: Int? = null
                if (quit) {
                    channel.send("sleep 30\n".toByteArray())
                    awaitPty { process.foregroundGroup() != shellGroup }
                    foreground = process.foregroundGroup()
                    process.requestProotExit()
                } else {
                    channel.send(byteArrayOf(4))
                }
                awaitPty { process.pollExit() != null }
                check(checkNotNull(process.pollExit()) in 0..255) { "PRoot did not exit through its event loop" }
                awaitPty {
                    !ptyExists(
                        background,
                    ) && !ptyExists(detached) && foreground?.let { !ptyExists(it) } != false
                }
                check(sentinel.pollExit() == null)
                println(
                    "Native PTY closure quit=$quit exit=${process.pollExit()} " +
                        "backgroundGone=true detachedGone=true sentinelAlive=true",
                )
            }
        }
    }

    fun failedExec() {
        val journal = PtyNativeFailureJournal(context)
        usePtyProcess(ProotPtyProcess.spawn(listOf("/helix-missing-executable"), emptyMap())) { process ->
            journal.spawned(process.pid)
            Channel(process).until("Helix PTY exec failed")
            awaitPty { process.pollExit() != null }
            check(process.pollExit() == 127)
            val tail = ByteArray(8192)
            awaitPty { process.read(tail) == -1 }
            journal.failedExecFinished(checkNotNull(process.pollExit()))
        }
    }

    fun signalAndLimits() {
        argumentLimits()
        check(
            runCatching {
                ProotPtyProcess.spawn(listOf("/system/bin/sh", "bad\u0000argument"), emptyMap())
            }.exceptionOrNull() is IllegalArgumentException,
        )
        val args =
            listOf(
                "/system/bin/sh",
                "-c",
                "printf 'ARG_%s\\n' \"\$1\"; exec /system/bin/sleep 30",
                "probe",
                "参数🙂",
            )
        usePtyProcess(ProotPtyProcess.spawn(args, emptyMap())) { process ->
            check(runCatching { process.resize(513, 80) }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { process.read(ByteArray(8193)) }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { process.write(byteArrayOf(1), 1, 1) }.exceptionOrNull() is IllegalArgumentException)
            Channel(process).until("ARG_参数🙂")
            process.killInitialGroup()
            awaitPty { process.pollExit() != null }
            check(process.pollExit() == 256 + OsConstants.SIGKILL)
        }
    }

    private fun argumentLimits() {
        val invalid = listOf(listOf("x".repeat(4097)), List(17) { "x".repeat(4096) }, List(128) { "x" })
        invalid.forEach { suffix ->
            check(
                runCatching {
                    ProotPtyProcess.spawn(listOf("/system/bin/sh") + suffix, emptyMap())
                }.exceptionOrNull() is IllegalArgumentException,
            )
        }
        check(
            runCatching {
                ProotPtyProcess.spawn(listOf("/system/bin/sh"), (1..129).associate { "K$it" to "v" })
            }.exceptionOrNull() is IllegalArgumentException,
        )
        check(
            runCatching {
                ProotPtyProcess.spawn(listOf("/system/bin/sh"), mapOf("KEY" to "x".repeat(4097)))
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    fun closeCycles() {
        cycle()
        val before = File("/proc/self/fd").list()!!.size
        repeat(20) { cycle() }
        val after = File("/proc/self/fd").list()!!.size
        println("Native PTY close cycles=20 fdBefore=$before fdAfter=$after")
        check(after <= before) { "Native PTY leaked descriptors: $before -> $after" }
    }

    private fun cycle() {
        usePtyProcess(ProotPtyProcess.spawn(listOf("/system/bin/sh", "-c", "exit 0"), emptyMap())) { process ->
            awaitPty { process.pollExit() != null }
            check(process.pollExit() == 0)
            check(process.reap() == 0)
            check(process.reap() == 0)
            process.closeMaster()
            process.closeMaster()
            check(runCatching { process.read(ByteArray(8)) }.exceptionOrNull() is IllegalStateException)
            check(runCatching { process.killInitialGroup() }.exceptionOrNull() is IllegalStateException)
            check(runCatching { process.requestProotExit() }.exceptionOrNull() is IllegalStateException)
        }
    }

    private fun prepare(): Pair<File, File> {
        val root = ProotRuntimeInstaller.runtimeRoot(context)
        if (RootFsInstaller.currentActive(root) == null) {
            val lock = ProotRuntimeInstaller.loadEmbeddedLock(context)
            val request =
                ProotRuntimeInstaller.buildInstallRequest(
                    context,
                    lock,
                    ProotNative.pageSizeBytes(),
                    System.currentTimeMillis(),
                )
            check(RootFsInstaller.install(request) is InstallOutcome.Success)
        }
        val install = File(root, checkNotNull(RootFsInstaller.currentActive(root)).installId)
        val loader = File(context.applicationInfo.nativeLibraryDir, "libhelix_loader.so")
        val digest = MessageDigest.getInstance("SHA-256")
        check(digest.digest(loader.readBytes()).contentEquals(digest.digest(File(install, "bin/loader").readBytes())))
        return install to loader
    }

    private class Channel(
        private val process: ProotPtyProcess,
    ) {
        private val input = PtyInputConnection()
        private var writer = checkNotNull(input.attach())
        private val output = PtyOutputBuffer("native-probe", "generation")
        private var cursor: String? = null

        fun reconnect() {
            val previous = writer
            check(input.attach() == null)
            check(input.detach(previous))
            writer = checkNotNull(input.attach())
            check(!input.detach(previous))
            check(input.offer(previous, "exit\n".toByteArray()) == PtyInputConnection.Admission.DETACHED)
        }

        fun send(bytes: ByteArray) {
            check(input.offer(writer, bytes) == PtyInputConnection.Admission.ACCEPTED)
            val chunk = checkNotNull(input.poll())
            var offset = 0
            awaitPty {
                offset += process.write(chunk, offset, chunk.size - offset)
                offset == chunk.size
            }
        }

        fun exchange(
            command: String,
            expected: String,
            prompt: String = PROMPT,
        ) {
            send(command.toByteArray(Charsets.UTF_8))
            until(expected, prompt)
        }

        fun until(
            expected: String,
            prompt: String = "",
        ): String {
            val received = ByteArrayOutputStream()
            val bytes = ByteArray(8192)
            awaitPty {
                val count = process.read(bytes)
                check(count >= 0) { "Unexpected PTY EOF: ${received.toString("UTF-8").takeLast(256)}" }
                output.append(bytes, count)
                val page = output.read(cursor)
                check(!page.gapBefore)
                cursor = page.cursor
                received.write(page.bytes)
                check(received.size() <= 65536)
                val text = received.toString("UTF-8")
                val start = text.indexOf(expected)
                start >= 0 && text.indexOf(prompt, start + expected.length) >= 0
            }
            return received.toString("UTF-8")
        }
    }

    companion object {
        private const val PROMPT = "helix-native> "
    }
}
