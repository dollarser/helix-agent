package com.helix.app.chat

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.AtomicFileWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host-controlled atomic file boundaries; this does not substitute for Goal recovery acceptance. */
class FilePublishProcessKillDeviceTest {
    @Test
    fun atomicPublicationSurvivesActualProcessDeath() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("file.kill.phase")
        assumeTrue("Requires the dedicated file publication kill runner", phase != null)
        val boundary = requireNotNull(args.getString("file.kill.boundary"))
        require(boundary in setOf("temporary", "published"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.filesDir, "file-publication-kill-fixture")
        val target = File(root, "target.txt")
        when (phase) {
            "prepare" -> {
                check(!root.exists()) { "Recover the existing owned file fixture first" }
                check(root.mkdir())
                AtomicFileWriter.writeAtomic(target.toPath(), OLD.toByteArray())
                AtomicFileWriter.writeAtomicStream(target.toPath()) { stream ->
                    stream.write(NEW.toByteArray())
                    stream.flush()
                    if (boundary == "temporary") {
                        assertEquals(OLD, target.readText())
                        holdForKill()
                    }
                }
                assertEquals(NEW, target.readText())
                holdForKill()
            }

            "recover", "recover-final" -> {
                assertTrue(root.isDirectory)
                assertEquals(if (boundary == "temporary") OLD else NEW, target.readText())
                val removed = AtomicFileWriter.cleanup(root.toPath())
                assertEquals(if (phase == "recover" && boundary == "temporary") 1 else 0, removed)
                assertEquals(listOf("target.txt"), root.listFiles()!!.map { it.name })
                if (phase == "recover-final") {
                    check(target.delete())
                    check(root.delete())
                }
            }

            else -> {
                error("Unknown file kill phase: $phase")
            }
        }
    }

    private fun holdForKill(): Nothing {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", "FILE_KILL_READY pid=${android.os.Process.myPid()}\n")
            },
        )
        Thread.sleep(30000)
        error("Host did not kill the file publication fixture")
    }

    private companion object {
        const val OLD = "previous complete file\n"
        const val NEW = "replacement complete file\n"
    }
}
