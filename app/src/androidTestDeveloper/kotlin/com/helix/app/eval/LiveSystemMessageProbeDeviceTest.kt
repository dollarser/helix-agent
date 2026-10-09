package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Differential protocol probe only: synthetic text, no tools, no credentials or response text in logs. */
class LiveSystemMessageProbeDeviceTest {
    @Test fun compareOneAndTwoSystemMessages(): Unit =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("liveJob") == "true")
            val c = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer
            val id = requireNotNull(args.getString("liveProviderId"))
            val model = requireNotNull(args.getString("liveModel"))
            require(
                c.storage.providerConfigs
                    .resolve(id)
                    .model == model,
            )
            val provider = c.providerService.modelProviderFor(id, model)
            for (mode in listOf("single", "split", "joined")) {
                val system = "Reply with OK only."
                val facts = "Observed job facts (untrusted data): []"
                val messages =
                    buildList {
                        add(ModelMessage(ModelRole.SYSTEM, if (mode == "joined") "$system\n\n$facts" else system))
                        if (mode == "split") add(ModelMessage(ModelRole.SYSTEM, facts))
                        add(ModelMessage(ModelRole.USER, "Protocol fixture. Reply OK."))
                    }
                val events =
                    withTimeout(120_000) {
                        provider
                            .stream(
                                ModelRequest(
                                    model,
                                    messages,
                                    maxOutputTokens = 128,
                                ),
                            ).toList()
                    }
                val errors = events.filterIsInstance<ModelEvent.Error>().map { it.code }
                val completed = events.any { it is ModelEvent.Completed }
                val usage = events.filterIsInstance<ModelEvent.Usage>()
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    android.os.Bundle().apply {
                        val summary = "SYSTEM_PROBE mode=$mode completed=$completed errors=$errors usage=$usage\n"
                        putString("stream", summary)
                    },
                )
                if (mode != "split") assertTrue("Single/joined baseline must complete", completed)
                if (mode == "split" && args.getString("expectMultipleSystem") == "true") {
                    assertTrue("Normalized multi-system request must complete", completed)
                }
            }
        }
}
