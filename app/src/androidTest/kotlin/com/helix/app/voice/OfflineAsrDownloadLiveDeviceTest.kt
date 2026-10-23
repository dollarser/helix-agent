package com.helix.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicit public-model network acceptance; never opens or grants the microphone. */
@RunWith(AndroidJUnit4::class)
class OfflineAsrDownloadLiveDeviceTest {
    @Test fun explicitlyDownloadedModelRecognizesPublicAudioWithoutMicrophone() =
        runBlocking {
            val acceptance = InstrumentationRegistry.getArguments().getString("asrDownloadAcceptance")
            assumeTrue(acceptance != null)
            require(acceptance == "true") { "asrDownloadAcceptance must be true when supplied" }
            val context = ApplicationProvider.getApplicationContext<Context>()
            check(context.packageName.endsWith(".developer.asrvalidation"))
            val model = LocalSpeechModel.asr(context)
            check(model.ready() == null) { "Acceptance requires a fresh owned test package" }
            try {
                model.download()
                assertNotNull(model.ready())
                assertNotNull(LocalSpeechModel.asr(context).ready())
                assertEquals(
                    PackageManager.PERMISSION_DENIED,
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
                )
                val text = recognize(context)
                assertTrue(text, text.contains("昨天"))
                assertTrue(text, text.contains("星期三"))
                File(context.cacheDir, "asr-download-acceptance.txt").writeText(
                    "download=verified\nreopen=verified\nmicrophone=not-granted\ntext=$text\n",
                )
            } finally {
                model.cancelDownload()
            }
        }

    private fun recognize(context: Context): String {
        val bytes =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open(
                    "asr-test/0.wav",
                ).use { it.readBytes() }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000, data.getInt(24))
        var offset = 12
        while (String(bytes, offset, 4, Charsets.US_ASCII) != "data") offset += 8 + data.getInt(offset + 4)
        val samples = FloatArray(data.getInt(offset + 4) / 2) { data.getShort(offset + 8 + it * 2) / 32768f }
        val asr = OfflineAsrFactory(context).open()
        return try {
            samples.asList().chunked(1600).forEach { asr.accept(it.toFloatArray()) }
            asr.finish()
        } finally {
            asr.close()
        }
    }
}
