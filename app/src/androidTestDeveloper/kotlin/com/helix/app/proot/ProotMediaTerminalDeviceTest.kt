package com.helix.app.proot

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.terminal.ManualTerminalActivity
import com.helix.core.model.SafetyProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual manual PTY -> guest wrapper -> packaged Bionic programs; no real accounts or external media. */
class ProotMediaTerminalDeviceTest {
    @Test
    fun versionsAndChineseFilenameAudioConversionRunWithoutLinkerErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ensureInstalledRuntime(context)
        val container = (context.applicationContext as HelixApplication).appContainer
        val terminal = checkNotNull(container.manualTerminal)
        val previous = container.profileStore.profile
        val relative = "media-regression-${UUID.randomUUID()}"
        val workspace = File(context.filesDir, "workspaces/app/terminal/$relative").apply { check(mkdirs()) }
        ActivityScenario.launch(ManualTerminalActivity::class.java).use {
            runBlocking {
                var session: String? = null
                try {
                    container.profileStore.switchTo(SafetyProfile.ADVANCED)
                    session = terminal.start(relative, 60_000).sessionId
                    val connection = terminal.attach(session)
                    try {
                        val command =
                            "ffmpeg -version > ffmpeg.txt 2> ffmpeg.err; echo \$? > ffmpeg.exit; " +
                                "ffprobe -version > ffprobe.txt 2> ffprobe.err; echo \$? > ffprobe.exit; " +
                                "python3 -c \"import wave; w=wave.open('输入.wav','wb'); " +
                                "w.setparams((1,2,16000,16000,'NONE','none')); " +
                                "w.writeframes(b'\\\\x00\\\\x00'*16000); w.close()\" && " +
                                "ffmpeg -nostdin -v error -i 输入.wav -c:a flac 输出.flac 2> convert.err; " +
                                "echo \$? > convert.exit; " +
                                "ffprobe -v error -show_entries stream=codec_name,sample_rate,channels " +
                                "-of json 输出.flac > probe.json 2> probe.err; echo \$? > probe.exit; " +
                                "printf done > complete\n"
                        assertEquals("ACCEPTED", connection.write(command.toByteArray(Charsets.UTF_8)))
                        withTimeout(45_000) { while (!File(workspace, "complete").exists()) delay(50) }
                        for (name in listOf("ffmpeg", "ffprobe", "convert", "probe")) {
                            assertEquals(
                                File(workspace, "$name.err").readText(),
                                "0",
                                File(workspace, "$name.exit").readText().trim(),
                            )
                            assertEquals("$name stderr", "", File(workspace, "$name.err").readText())
                        }
                        assertTrue(File(workspace, "ffmpeg.txt").readText().contains("ffmpeg version"))
                        assertTrue(File(workspace, "ffprobe.txt").readText().contains("ffprobe version"))
                        val probe = File(workspace, "probe.json").readText()
                        assertTrue(probe, probe.contains("flac") && probe.contains("16000"))
                        assertTrue(File(workspace, "输出.flac").length() > 0)
                    } finally {
                        connection.detach()
                    }
                } finally {
                    session?.let { owned ->
                        terminal.stop(owned)
                        withTimeout(15_000) { while (!terminal.query(owned).canSettle) delay(50) }
                        terminal.settle(owned)
                    }
                    container.profileStore.switchTo(previous)
                    workspace.deleteRecursively()
                }
            }
        }
    }
}
