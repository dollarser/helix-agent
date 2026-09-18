package com.helix.app.proot

import com.helix.runtime.proot.client.ProotLogClient
import com.helix.runtime.proot.core.JobLogText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal object CommandLogReader {
    fun observe(binding: CommandJobBindingFacts) =
        flow {
            val client = ProotLogClient()
            val text = JobLogText()
            var cursor: String? = null
            var waiting = 0
            var done = false
            while (!done) {
                val page = client.read(binding.jobId, binding.inputManifestSha256, cursor)
                if (page == null) {
                    // Bounded queue-start wait; no binding or restart of a lost generation.
                    done = cursor != null || waiting++ >= 8
                    if (done) emit(CommandLiveOutput(text.stdout, text.stderr, text.truncated, unavailable = true))
                    delay(250)
                } else {
                    text.append(page)
                    cursor = page.cursor
                    emit(CommandLiveOutput(text.stdout, text.stderr, text.truncated))
                    done = page.eof
                    delay(if (page.bytes.isEmpty()) 250 else 25)
                }
            }
        }.flowOn(Dispatchers.IO)
}
