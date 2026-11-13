@file:Suppress("LongMethod", "MagicNumber")

package com.helix.app.proot

import android.graphics.BitmapFactory
import android.media.MediaCodecList
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.terminal.ManualTerminalActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic local media through the production PTY and bundled Bionic-in-PRoot bridge. */
class ProotMediaWorkflowDeviceTest {
    @Test
    fun softwareVideoAudioFontsAndAllMediaCodecModesUseActualGuestBridge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ensureInstalledRuntime(context)
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }.forEach { codec ->
            if (codec.supportedTypes.any { it == "video/avc" || it == "video/hevc" }) {
                println(
                    "HXA240 codec=${codec.name} " +
                        "hardware=${codec.isHardwareAccelerated} software=${codec.isSoftwareOnly}",
                )
            }
        }
        val terminal = checkNotNull((context.applicationContext as HelixApplication).appContainer.manualTerminal)
        val relative = "media-workflow-${UUID.randomUUID()}"
        val workspace = File(context.filesDir, "workspaces/app/terminal/$relative").apply { check(mkdirs()) }
        // Two seconds of independent raw fixtures; no external media, codecs or network downloads.
        File(workspace, "input.yuv").writeBytes(
            ByteArray(96 * 64 * 3 / 2 * 8) { index ->
                val pixel = index % (96 * 64 * 3 / 2)
                (if (pixel < 96 * 64) 32 + (pixel + index / (96 * 64 * 3 / 2) * 16) % 128 else 128).toByte()
            },
        )
        File(workspace, "input.pcm").writeBytes(ByteArray(16000 * 2 * 2))
        File(workspace, "captions.srt").writeText("1\n00:00:00,000 --> 00:00:02,000\nHelix subtitles\n")
        File(workspace, "workflow.sh").writeText(
            """
            set -eu
            ffmpeg -nostdin -v error -f rawvideo -pixel_format yuv420p -video_size 96x64 -framerate 4 -i input.yuv -f s16le -ar 16000 -ac 1 -i input.pcm -c:v mpeg4 -g 4 -q:v 3 -c:a aac -shortest '视频 input.mp4'
            ffprobe -v error -count_frames -show_streams -of json '视频 input.mp4' > streams.json
            ffmpeg -nostdin -v error -i '视频 input.mp4' -map 0:v -c copy copied.mp4 -map 0:a -c:a flac audio.flac
            ffmpeg -nostdin -v error -ss 0.5 -i '视频 input.mp4' -vf crop=64:48:0:0,scale=128:96,transpose=1 -frames:v 1 crop.png
            ffprobe -v error -show_streams -of json crop.png > crop.json
            ffmpeg -nostdin -v error -i '视频 input.mp4' -frames:v 1 plain.png
            ffmpeg -nostdin -v error -i '视频 input.mp4' -vf 'drawtext=fontfile=/system/fonts/Roboto-Regular.ttf:text=Helix:fontcolor=white:fontsize=16:x=0:y=0' -frames:v 1 text.png
            ffmpeg -nostdin -v error -i '视频 input.mp4' -vf 'subtitles=captions.srt:fontsdir=/system/fonts:force_style=Fontname=Roboto' -frames:v 1 subtitles.png
            ffmpeg -nostdin -v error -i audio.flac -f s16le decoded.pcm
            ffmpeg -nostdin -hide_banner -encoders > encoders.txt 2>&1
            ffmpeg -nostdin -hide_banner -protocols > protocols.txt 2>&1
            # Qualcomm byte-buffer encoders require a supported size and semi-planar input.
            for codec in h264 hevc; do
              for mode in 0 1; do
                name="${'$'}codec-${'$'}mode"
                if timeout -k 2 15 ffmpeg -nostdin -v error -i '视频 input.mp4' -an -vf scale=320:240 -pix_fmt nv12 -c:v "${'$'}{codec}_mediacodec" -ndk_codec 1 -ndk_async "${'$'}mode" -flags -global_header -bsf:v extract_extradata "${'$'}name.mp4" > "${'$'}name.log" 2>&1; then
                  if ffprobe -v error -count_frames -show_streams -of json "${'$'}name.mp4" > "${'$'}name.json" 2>> "${'$'}name.log" && ffmpeg -nostdin -v error -i "${'$'}name.mp4" -f null - >> "${'$'}name.log" 2>&1; then
                    echo supported > "${'$'}name.status"
                  else
                    echo invalid-output > "${'$'}name.status"
                  fi
                else
                  code=${'$'}?
                  echo "encoder exit ${'$'}code" >> "${'$'}name.log"
                  echo unavailable > "${'$'}name.status"
                fi
              done
            done
            if ffmpeg -nostdin -v error -i absent-input.mp4 invalid.mp4 > invalid.log 2>&1; then exit 91; fi
            """.trimIndent(),
        )
        ActivityScenario.launch(ManualTerminalActivity::class.java).use { activity ->
            runBlocking {
                var session: String? = null
                try {
                    session = terminal.start(relative, 180_000).sessionId
                    val connection = terminal.attach(session)
                    try {
                        assertEquals(
                            "ACCEPTED",
                            connection.write(
                                "sh workflow.sh > workflow.log 2>&1; echo \$? > workflow.exit\n".toByteArray(),
                            ),
                        )
                        withTimeout(150_000) { while (!File(workspace, "workflow.exit").exists()) delay(100) }
                        val log = File(workspace, "workflow.log").readText()
                        assertEquals(log, "0", File(workspace, "workflow.exit").readText().trim())
                        val streams = JSONObject(File(workspace, "streams.json").readText()).getJSONArray("streams")
                        assertEquals(2, streams.length())
                        assertEquals("mpeg4", streams.getJSONObject(0).getString("codec_name"))
                        assertEquals(8, streams.getJSONObject(0).getInt("nb_read_frames"))
                        assertEquals("aac", streams.getJSONObject(1).getString("codec_name"))
                        val crop =
                            JSONObject(
                                File(workspace, "crop.json").readText(),
                            ).getJSONArray("streams").getJSONObject(0)
                        assertEquals(96, crop.getInt("width"))
                        assertEquals(128, crop.getInt("height"))
                        for (file in listOf("copied.mp4", "audio.flac", "decoded.pcm", "text.png", "subtitles.png")) {
                            assertTrue(file, File(workspace, file).length() > 0)
                        }
                        val plain = BitmapFactory.decodeFile(File(workspace, "plain.png").path)
                        assertFalse(plain.sameAs(BitmapFactory.decodeFile(File(workspace, "text.png").path)))
                        assertFalse(plain.sameAs(BitmapFactory.decodeFile(File(workspace, "subtitles.png").path)))
                        assertFalse(
                            File(workspace, "protocols.txt").readText().lineSequence().any { it.trim() == "http" },
                        )
                        assertTrue(File(workspace, "invalid.log").readText().contains("No such file"))
                        for (codecName in listOf("h264", "hevc")) {
                            for (mode in 0..1) {
                                val name = "$codecName-$mode"
                                val status = File(workspace, "$name.status").readText().trim()
                                val diagnostic = File(workspace, "$name.log").readText()
                                println("HXA240 MediaCodec $name=$status\n$diagnostic")
                                assertEquals(diagnostic, "supported", status)
                                val codec =
                                    JSONObject(
                                        File(workspace, "$name.json").readText(),
                                    ).getJSONArray("streams").getJSONObject(0)
                                assertEquals(codecName, codec.getString("codec_name"))
                                assertEquals(8, codec.getInt("nb_read_frames"))
                                assertEquals(320, codec.getInt("width"))
                                assertEquals(240, codec.getInt("height"))
                            }
                        }
                        println(
                            "HXA240 software media, multi-output, transforms, fonts, missing input and no HTTP passed",
                        )
                    } finally {
                        connection.detach()
                    }
                } finally {
                    // Close the UI reclamation owner before explicitly settling this test terminal.
                    activity.close()
                    session?.let { owned ->
                        terminal.stop(owned)
                        withTimeout(15_000) { while (!terminal.query(owned).canSettle) delay(50) }
                        terminal.settle(owned)
                    }
                    workspace.deleteRecursively()
                }
            }
        }
    }
}
