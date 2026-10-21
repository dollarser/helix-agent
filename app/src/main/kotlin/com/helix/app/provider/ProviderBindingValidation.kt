package com.helix.app.provider

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.transportIdentity

/** Rendering uses observed rows; admission checks durable existence and exact transport evidence. */
internal class ProviderBindingValidation(
    private val storage: HelixStorage,
    private val tests: ProviderTestStatusStore,
    private val rows: () -> List<ProviderRowUi>,
    private val managed: (String) -> Boolean,
    private val now: () -> Long,
) {
    suspend fun validate(
        providerId: String?,
        model: String?,
    ): ProviderBindingIssue? =
        if (providerId == null) {
            missingSource(model)
        } else {
            val entity = storage.providerConfigs.list().find { it.id == providerId }
            issue(providerId, model, entity != null, entity?.transportIdentity)
        }

    fun project(
        providerId: String?,
        model: String?,
    ): ProviderBindingIssue? =
        if (providerId == null) {
            missingSource(model)
        } else {
            issue(providerId, model, rows().any { it.id == providerId }, null)
        }

    private fun missingSource(model: String?): ProviderBindingIssue? =
        if (model != null) ProviderBindingIssue.SOURCE_DELETED else null

    private fun issue(
        id: String,
        model: String?,
        exists: Boolean,
        identity: String?,
    ): ProviderBindingIssue? =
        when {
            !exists -> {
                ProviderBindingIssue.SOURCE_DELETED
            }

            model.isNullOrBlank() -> {
                ProviderBindingIssue.MODEL_MISSING
            }

            BuiltInModel.owns(id) -> {
                builtInBindingIssue(tests.builtInCatalog.accountAvailable(), tests.builtInCatalog.read(), model, now())
            }

            else -> {
                providerBindingIssue(
                    exists = true,
                    changed = id in tests.changedConfigurations,
                    status = tests.statusFor(id),
                    account = if (managed(id)) tests.accounts.read(id) else null,
                    modelFailure =
                        if (identity != null) {
                            tests.modelEvidence
                                .read(id, identity)
                                .generations[model]
                                ?.failure
                        } else {
                            rows()
                                .find { it.id == id }
                                ?.modelGenerations
                                ?.get(model)
                                ?.failure
                        },
                )
            }
        }
}
