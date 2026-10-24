package com.helix.app.application.agent

import com.helix.core.agent.RunControlConfig
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.SessionInputRecord

/** One configuration identity shared by accepted-input creation and admission revalidation. */
internal object SessionInputConfiguration {
    fun hash(
        providerFacts: String,
        control: RunControlConfig,
        selectedMode: String,
    ): String {
        // A default for future messages cannot invalidate an already accepted input.
        val executionControl = control.copy(immediateMessages = false)
        return FileContentStore.sha256Hex(
            "$providerFacts\n$executionControl\n$selectedMode".toByteArray(Charsets.UTF_8),
        )
    }

    fun matches(
        input: SessionInputRecord,
        providerFacts: String,
        control: RunControlConfig,
        selectedMode: String,
    ): Boolean =
        input.configuration.fingerprint.substringBeforeLast(':') == "v1:${hash(providerFacts, control, selectedMode)}"
}
