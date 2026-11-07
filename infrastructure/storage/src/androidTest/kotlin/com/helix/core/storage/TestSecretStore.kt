package com.helix.core.storage

import com.helix.core.model.SecretAlias

/**
 * In-memory [SecretStore] for storage fixture tests that exercise the repositories rather
 * than the Keystore itself (the real [AndroidKeystoreSecretStore] is covered by its own
 * instrumented test). Not thread-safe by design: the fixture tests are single-threaded.
 */
class TestSecretStore : SecretStore {
    private val values = LinkedHashMap<SecretAlias, String>()

    override fun put(
        alias: SecretAlias,
        secret: String,
    ) {
        values[alias] = secret
    }

    override fun get(alias: SecretAlias): String =
        values[alias] ?: throw IllegalArgumentException("no secret stored for alias: $alias")

    override fun delete(alias: SecretAlias) {
        values.remove(alias)
    }

    override fun contains(alias: SecretAlias): Boolean = values.containsKey(alias)

    override fun aliases(): Set<SecretAlias> = values.keys.toSet()
}
