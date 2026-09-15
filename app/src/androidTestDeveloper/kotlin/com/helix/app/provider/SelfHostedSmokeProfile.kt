package com.helix.app.provider

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/** Never probe a host service merely because it happens to be reachable. */
fun requireSelfHostedSmoke() {
    assumeTrue(
        "realSelfHosted=true is required for real Ollama/sglang network smoke",
        InstrumentationRegistry.getArguments().getString("realSelfHosted") == "true",
    )
}
