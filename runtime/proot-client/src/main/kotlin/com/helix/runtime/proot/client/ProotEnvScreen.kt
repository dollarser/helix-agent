package com.helix.runtime.proot.client

/**
 * The job environment screen (HXA-084; architecture doc section 6.5).
 *
 * The Runtime never gains any SecretStore capability: the environment that
 * reaches the companion is produced HERE, in the main process, and a variable
 * is admitted only when its NAME is on the allowlist (plus task-explicit
 * additions) AND none of the rejection rules fires. Rejections are checked
 * before allowlisting — a secret-named variable is never admissible, even if a
 * caller explicitly adds its name.
 *
 * Rejection reasons name the VARIABLE but never its value (a rejection is
 * logged/persisted; values are not).
 *
 * Pure and JVM-testable: no Android types.
 */
object ProotEnvScreen {
    /**
     * The default allowlist: plain, non-secret environment names a job may
     * legitimately need. Anything else requires an explicit per-task addition
     * (which still passes every rejection rule).
     */
    val DEFAULT_ALLOWLIST: Set<String> =
        setOf(
            "HOME",
            "PATH",
            "LANG",
            "LC_ALL",
            "TMPDIR",
            "TERM",
            "USER",
            "SHELL",
        )

    /**
     * Closed-set secret NAME patterns (task text: KEY/TOKEN/SECRET/PASSWORD/
     * AUTH/COOKIE/CREDENTIAL, matched case-insensitively against the name).
     */
    private val SECRET_NAME_PATTERN =
        Regex(".*(KEY|TOKEN|SECRET|PASSWORD|PASSWD|AUTH|COOKIE|CREDENTIAL).*", RegexOption.IGNORE_CASE)

    /**
     * Values that identify an AUTHORIZATION STRUCTURE (task text: 认证来源):
     * HTTP auth-scheme prefixes and PEM private-key material. These are
     * rejected by shape regardless of name — a credential blob is a credential
     * even under an innocent variable name.
     */
    private fun isAuthStructure(value: String): Boolean {
        if (value.isEmpty()) return false
        val lower = value.lowercase()
        return lower.startsWith("bearer ") ||
            lower.startsWith("basic ") ||
            lower.startsWith("authorization:") ||
            (lower.contains("-----begin") && lower.contains("private key"))
    }

    /** One rejection. [name] is the variable; the value is NEVER part of a reason. */
    data class Reason(
        val kind: Kind,
        val name: String,
    ) {
        enum class Kind {
            /** The name is neither in the allowlist nor task-explicitly added. */
            NOT_ALLOWED,

            /** The name matches a secret pattern (never admissible). */
            SECRET_NAME,

            /** The value equals a known SecretStore value (defense in depth). */
            KNOWN_SECRET_VALUE,

            /** The value has the shape of an authorization structure. */
            AUTH_STRUCTURE,
        }
    }

    /** The screening verdict. */
    sealed interface Verdict {
        /** The screened (name-sorted) environment; safe to hand to the Runtime. */
        data class Approved(
            val environment: Map<String, String>,
        ) : Verdict

        /** Every rejected variable, with a value-free reason. */
        data class Rejected(
            val reasons: List<Reason>,
        ) : Verdict
    }

    /**
     * Screens [requested]. [knownSecretValues] are the values currently held in
     * the main app's SecretStore (the screen compares by value so a renamed
     * secret is still caught); [extraAllowedNames] are task-explicit additions
     * to the allowlist. An empty request is legal (the runner adds its own
     * fixed variables).
     */
    fun screen(
        requested: Map<String, String>,
        knownSecretValues: Set<String>,
        extraAllowedNames: Set<String> = emptySet(),
    ): Verdict {
        val reasons =
            requested
                .toSortedMap()
                .map { (name, value) -> screenEntry(name, value, knownSecretValues, extraAllowedNames) }
                .filterNotNull()
        return if (reasons.isEmpty()) {
            Verdict.Approved(requested.toSortedMap())
        } else {
            Verdict.Rejected(reasons)
        }
    }

    /**
     * The rejection rule for one variable, in fixed priority order (a
     * secret-named variable is never admissible, even when explicitly
     * allowed). Null when the variable passes every rule. One return per
     * rejection rule.
     */
    @Suppress("ReturnCount")
    private fun screenEntry(
        name: String,
        value: String,
        knownSecretValues: Set<String>,
        extraAllowedNames: Set<String>,
    ): Reason? {
        if (name.isBlank()) return Reason(Reason.Kind.NOT_ALLOWED, name)
        if (SECRET_NAME_PATTERN.matches(name)) return Reason(Reason.Kind.SECRET_NAME, name)
        if (value.isNotEmpty() && value in knownSecretValues) {
            return Reason(Reason.Kind.KNOWN_SECRET_VALUE, name)
        }
        if (isAuthStructure(value)) return Reason(Reason.Kind.AUTH_STRUCTURE, name)
        if (name !in DEFAULT_ALLOWLIST && name !in extraAllowedNames) {
            return Reason(Reason.Kind.NOT_ALLOWED, name)
        }
        return null
    }
}
