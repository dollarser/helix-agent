package com.helix.core.agent

import com.helix.core.agent.RunControlConfig
import com.helix.core.model.input.InputConfiguration
import java.security.MessageDigest

/** One configuration identity shared by accepted-input creation and admission revalidation. */
object SessionInputConfiguration {
    fun hash(
        providerFacts: String,
        control: RunControlConfig,
        selectedMode: String,
    ): String {
        // A default for future messages cannot invalidate an already accepted input.
        val executionControl = control.copy(immediateMessages = false)
        return MessageDigest
            .getInstance("SHA-256")
            .digest("$providerFacts\n$executionControl\n$selectedMode".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun matches(
        configuration: InputConfiguration,
        providerFacts: String,
        control: RunControlConfig,
        selectedMode: String,
    ): Boolean =
        configuration.fingerprint.substringBeforeLast(':') == "v1:${hash(providerFacts, control, selectedMode)}"
}
