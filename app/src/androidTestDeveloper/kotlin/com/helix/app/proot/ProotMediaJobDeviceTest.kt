package com.helix.app.proot

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.application.tools.FileToolArguments
import com.helix.core.workspace.FileScopePath
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real Dispatcher -> detached Job -> media bridge -> original result collection, not a shell mock. */
class ProotMediaJobDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()

    @Suppress("LongMethod", "MagicNumber")
    @Test
    fun largeSnapshotAudioPublishesMultipleFilesAndRecollectionKeepsOriginalBytes() {
        DetachedJobJourneyFixture(app).use { f ->
            f.start(
                "set -eu; mkdir -p output; " +
                    "ffmpeg -nostdin -v error -f s16le -ar 16000 -ac 1 -i /workspace/input.pcm " +
                    "-t 1 -c:a flac output/audio.flac; " +
                    "ffprobe -v error -show_streams -of json output/audio.flac > output/probe.json; " +
                    "ffmpeg -nostdin -v error -i output/audio.flac -f s16le output/decoded.pcm; " +
                    "printf media-complete > result.txt",
            ) { args ->
                val workspaceId = requireNotNull(f.storage.workspaces.binding(f.id)).workspaceId
                val root = f.storage.workspaces.managedDirectory(workspaceId)
                root.resolve("input.pcm").toFile().writeBytes(ByteArray(1_200_000))
                FileToolArguments.normalize(
                    JsonObject(
                        args +
                            mapOf(
                                "workspaceMode" to JsonPrimitive("snapshot"),
                                "files" to JsonArray(listOf(JsonPrimitive("input.pcm"))),
                            ),
                    ),
                    FileScopePath(workspaceId, ""),
                )
            }
            val record = f.awaitTerminal()
            assertEquals(ProotJobState.SUCCEEDED, record.state)
            repeat(2) {
                assertEquals(
                    BackgroundJobActionOutcome.SETTLED,
                    ProotToolModule.performBackgroundJobAction(f.job, BackgroundJobAction.COLLECT) { false },
                )
                val output = File(app.filesDir, "workspaces/app/output/jobs/${record.jobId}")
                assertTrue(File(output, "audio.flac").length() > 0)
                assertEquals(32_000L, File(output, "decoded.pcm").length())
                assertTrue(File(output, "decoded.pcm").readBytes().all { byte -> byte == 0.toByte() })
                val probe = File(output, "probe.json").readText()
                assertTrue(probe, probe.contains("flac") && probe.contains("16000"))
                val artifacts = f.storage.artifacts.listBySession(f.id)
                assertEquals(3, artifacts.count { artifact -> artifact.id.startsWith("proot-file-") })
            }
            assertEquals(
                1,
                f.storage.toolCalls
                    .listByTurn(f.id)
                    .size,
            )
        }
    }
}
