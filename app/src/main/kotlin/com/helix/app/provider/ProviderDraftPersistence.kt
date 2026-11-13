package com.helix.app.provider

import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.transportIdentity
import com.helix.core.storage.repository.ProviderConfigRepository
import com.helix.core.storage.repository.ProviderConfigSpec

/** Called under the service mutation gate; writes and compensations do not suspend. */
internal class ProviderDraftPersistence(
    private val configs: ProviderConfigRepository,
    private val secrets: SecretStore,
    private val status: ProviderTestStatusStore,
    private val debugApis: DebugCustomApiStore,
    private val idGenerator: () -> String,
) {
    fun save(
        session: ProviderDraftSaveSession,
        draft: ProviderDraft,
        apiKey: String?,
        selection: ProviderModelSelection,
    ) {
        if (session.recoveryRequired) throw ProviderDraftSaveRecoveryRequiredException()
        val existing = configs.find(session.providerId)
        if (session.completed) {
            verifyCompleted(session, existing, draft, apiKey, selection)
            return
        }
        verifyBaseline(session, existing, draft, apiKey)
        val previousDebug = existing?.let { debugDraft(it) }
        val alias = if (apiKey.isNullOrBlank()) existing?.secretAlias else idGenerator()
        val changed = existing == null || providerConnectionChanged(configFrom(existing), draft, apiKey)
        val spec = draftSpec(session.providerId, draft, alias, existing, changed)
        persistProviderDraft(
            session,
            write = {
                if (!apiKey.isNullOrBlank()) secrets.put(SecretAlias(requireNotNull(alias)), apiKey)
                writeDraft(session, draft, selection, existing, spec, changed)
            },
            restore = {
                restoreDraft(session, existing, previousDebug)
                if (!apiKey.isNullOrBlank()) secrets.delete(SecretAlias(requireNotNull(alias)))
            },
            verifyRestored = {
                configs.find(session.providerId) == existing &&
                    status.selectedModels.selection(session.providerId) == session.expectedSelection
            },
        )
        if (!apiKey.isNullOrBlank()) removePreviousKey(session, existing)
    }

    private fun verifyBaseline(
        session: ProviderDraftSaveSession,
        existing: ProviderConfigEntity?,
        draft: ProviderDraft,
        apiKey: String?,
    ) {
        if (!sameProviderEditableConfig(existing?.let(::configFrom), session.expectedConfig) ||
            status.selectedModels.selection(session.providerId) != session.expectedSelection
        ) {
            throw ProviderDraftSaveConflictException()
        }
        require(existing == null || existing.provisioningKind == "USER_CONFIGURED")
        require(
            !draft.credentialRequired || !apiKey.isNullOrBlank() ||
                existing?.secretAlias?.takeUnless { it == ProviderFactory.NO_KEY_ALIAS } != null,
        )
    }

    /** Lost callbacks may retry the same save, but changed drafts must never receive false success. */
    private fun verifyCompleted(
        session: ProviderDraftSaveSession,
        existing: ProviderConfigEntity?,
        draft: ProviderDraft,
        apiKey: String?,
        selection: ProviderModelSelection,
    ) {
        val sameKey = apiKey.isNullOrBlank() || existing?.secretAlias?.let { secrets.get(SecretAlias(it)) } == apiKey
        val samePreferences =
            status.selectedModels.selection(session.providerId) == selection &&
                existing?.displayName == draft.displayName
        val sameConnection = existing != null && !providerConnectionChanged(configFrom(existing), draft, null)
        if (!sameConnection || !sameKey || !samePreferences) {
            throw ProviderDraftSaveConflictException()
        }
    }

    @Suppress("LongParameterList")
    private fun writeDraft(
        session: ProviderDraftSaveSession,
        draft: ProviderDraft,
        selection: ProviderModelSelection,
        existing: ProviderConfigEntity?,
        spec: ProviderConfigSpec,
        changed: Boolean,
    ) {
        // Invalidate before exposing the new connection. Lost evidence is safe after compensation.
        if (changed) {
            status.clear(session.providerId, configurationChanged = existing != null)
            if (existing != null) status.modelMetadata.write(existing.id, existing.transportIdentity, emptyMap())
        }
        debugApis.record(session.providerId, draft)
        if (selection.configured) {
            status.selectedModels.save(session.providerId, selection, session.expectedSelection)
        } else {
            status.selectedModels.clear(session.providerId)
        }
        if (existing == null) configs.save(spec) else configs.overwrite(spec)
        check(status.selectedModels.selection(session.providerId) == selection)
    }

    private fun restoreDraft(
        session: ProviderDraftSaveSession,
        existing: ProviderConfigEntity?,
        previousDebug: ProviderDraft?,
    ) {
        if (existing != null) {
            configs.overwrite(restoreSpec(existing))
        } else if (configs.find(session.providerId) != null) {
            configs.delete(session.providerId)
        }
        if (session.expectedSelection.configured) {
            status.selectedModels.save(session.providerId, session.expectedSelection)
        } else {
            status.selectedModels.clear(session.providerId)
        }
        if (previousDebug == null) {
            debugApis.clear(session.providerId)
        } else {
            debugApis.record(session.providerId, previousDebug)
        }
    }

    // Any cleanup failure must remain visible; the new settings already committed.
    @Suppress("TooGenericExceptionCaught")
    private fun removePreviousKey(
        session: ProviderDraftSaveSession,
        existing: ProviderConfigEntity?,
    ) {
        existing?.secretAlias?.takeUnless { it == ProviderFactory.NO_KEY_ALIAS }?.let {
            try {
                secrets.delete(SecretAlias(it))
            } catch (failure: Exception) {
                session.recoveryRequired = true
                throw ProviderDraftSaveRecoveryRequiredException(failure)
            }
        }
    }

    private fun debugDraft(entity: ProviderConfigEntity): ProviderDraft {
        val config = configFrom(entity)
        return ProviderDraft(
            templateId = if (debugApis.enabled(config)) "generic-openai" else null,
            displayName = entity.displayName,
            protocol = config.protocol,
            endpoint = config.endpoint,
            model = entity.model,
            headersJson = entity.headersJson,
            credentialRequired = false,
        )
    }

    private fun draftSpec(
        id: String,
        draft: ProviderDraft,
        alias: String?,
        existing: ProviderConfigEntity?,
        changed: Boolean,
    ) = ProviderConfigSpec(
        id = id,
        displayName = draft.displayName,
        protocol = draft.protocol,
        endpoint = draft.endpoint.full,
        model = draft.model,
        headersJson = draft.headersJson,
        secretAlias = alias,
        authKind = if (alias == null) "NONE" else "SECRET",
        capabilitySnapshot = if (changed) UNTESTED_PROVIDER_SNAPSHOT else requireNotNull(existing).capabilitySnapshot,
    )

    private fun restoreSpec(entity: ProviderConfigEntity) =
        ProviderConfigSpec(
            id = entity.id,
            displayName = entity.displayName,
            protocol = entity.protocol?.let(ProviderProtocol::parse),
            endpoint = entity.endpoint,
            model = entity.model,
            headersJson = entity.headersJson,
            secretAlias = entity.secretAlias,
            capabilitySnapshot = entity.capabilitySnapshot,
            provisioningKind = entity.provisioningKind,
            transportKind = entity.transportKind,
            authKind = entity.authKind,
        )
}
