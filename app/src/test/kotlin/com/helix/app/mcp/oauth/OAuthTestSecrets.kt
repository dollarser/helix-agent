package com.helix.app.mcp.oauth

import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import java.util.concurrent.ConcurrentHashMap

internal class OAuthTestSecrets : SecretStore {
    private val values = ConcurrentHashMap<SecretAlias, String>()

    override fun put(
        alias: SecretAlias,
        secret: String,
    ) {
        values[alias] = secret
    }

    override fun get(alias: SecretAlias): String = requireNotNull(values[alias])

    override fun delete(alias: SecretAlias) {
        values.remove(alias)
    }

    override fun contains(alias: SecretAlias): Boolean = values.containsKey(alias)

    override fun aliases(): Set<SecretAlias> = values.keys.toSet()
}
