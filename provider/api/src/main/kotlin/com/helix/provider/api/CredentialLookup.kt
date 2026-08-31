package com.helix.provider.api

import com.helix.core.model.SecretAlias

/**
 * Resolves a [SecretAlias] to the plaintext credential at request time (HXA-025).
 *
 * The implementation is backed by the Keystore [SecretAlias] store (HXA-020:
 * `SecretStore`, alias-only, no plaintext at rest) and is injected at provider
 * construction — the provider modules are pure JVM and never depend on the
 * Android storage module. A lookup failure must throw: the request is never
 * sent with a missing credential (fail closed), and the exception message must
 * not contain the credential itself.
 */
public fun interface CredentialLookup {
    public fun lookup(alias: SecretAlias): String
}
