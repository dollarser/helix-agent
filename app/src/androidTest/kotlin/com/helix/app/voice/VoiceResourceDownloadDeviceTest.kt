package com.helix.app.voice

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit public resource download only; never sends text or uses the user's installed model directory. */
class VoiceResourceDownloadDeviceTest {
    @Test fun explicitPinnedPublicDownloadVerifiesAndPublishes(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("helix.ttsDownload")
            require(flag == null || flag in setOf("true", "false"))
            assumeTrue(flag == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.cacheDir, "tts-public-${UUID.randomUUID()}")
            val model = LocalVoiceModel(context, root)
            try {
                assertNull(model.ready())
                model.download()
                assertNotNull(model.ready())
                assertNull(model.downloadedBytes.value)
            } finally {
                model.cancelDownload()
                root.deleteRecursively()
            }
        }
}
