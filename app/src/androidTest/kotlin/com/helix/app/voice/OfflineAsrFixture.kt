package com.helix.app.voice

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File

/** Public qualified weights from the test APK only, never from production assets or the network. */
internal fun installOfflineAsrFixture(context: Context) {
    check(context.packageName.endsWith(".asrvalidation"))
    val model = LocalSpeechModel.asr(context)
    val target = File(context.filesDir, "asr/zipformer-int8").apply { mkdirs() }
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    model.parts.forEach { part ->
        assets.open("asr-model/${part.name}").use { source ->
            File(target, part.name).outputStream().use { output -> source.copyTo(output) }
        }
    }
    checkNotNull(runBlocking { model.ready() })
}
