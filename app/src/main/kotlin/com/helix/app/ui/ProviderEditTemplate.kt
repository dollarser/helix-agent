package com.helix.app.ui

import com.helix.provider.api.ProviderConfig
import com.helix.provider.catalog.ProviderTemplate
import com.helix.provider.catalog.ProviderTemplateCatalog

/**
 * The template an edit dialog composes against. The persisted row does not
 * store the template id, so the template is re-resolved from the protocol: an
 * exact endpoint match (same template family) wins, then the generic (no
 * default endpoint) template of the protocol, then any template of the
 * protocol. For a re-pointed endpoint the generic template's headers replace
 * the old attribution headers — the honest outcome, since the endpoint is no
 * longer that vendor's.
 */
internal fun editTemplateFor(
    config: ProviderConfig,
    hasStoredKey: Boolean,
): ProviderTemplate {
    val candidates = ProviderTemplateCatalog.all.filter { it.protocol == config.protocol }
    val template =
        candidates.firstOrNull { it.defaultEndpoint == config.endpoint }
            ?: candidates.firstOrNull { it.defaultEndpoint == null }
            ?: candidates.first()
    // The edit template is a GUESS (endpoint match; a self-hosted provider on a
    // non-default endpoint falls back to the generic one, whose credentialRequired
    // is true). That guess must not invent a key requirement the persisted
    // provider never had: a provider stored WITHOUT a key was created through a
    // keyless form, so re-requiring a key on edit silently disables 保存
    // (device-verified in the HXA-059 arbitration: chip-prefill → save dead on
    // self-hosted providers). With a stored key the template stands (keyOk is
    // true regardless of credentialRequired).
    return if (hasStoredKey) template else template.copy(credentialRequired = false)
}
