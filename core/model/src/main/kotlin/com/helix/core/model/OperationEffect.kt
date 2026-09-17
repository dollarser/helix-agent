package com.helix.core.model

/**
 * The CLOSED, versioned set of operation-effect keys a session permission rule governs
 * (ADR-PERMISSIONS-001 section 1.2). A tool call's effect footprint is a set of these keys;
 * the resolver merges EVERY effect of the call before deciding (any DENY refuses the whole
 * call, any ASK asks once for the whole call, only an all-ALLOW footprint skips the card).
 *
 * The set is deliberately closed and there is no executable policy DSL: an effect with no key
 * here (e.g. a plain network read) is not gated by the mode beyond the hard policy gates, and
 * a FUTURE new category must default to ASK — never silently become ALLOW (ADR section 1.2).
 *
 * File delete IS file mutation: no separate delete key exists. The `rm -rf` command rule
 * (ADR section 3) is a separate limited reminder layer on top of these keys, not an eighth
 * category, and matching it never weakens the cross-tool file-mutation restriction.
 */
enum class OperationEffect {
    /** Reading files inside the session's bound workspace. */
    FILE_READ_WORKSPACE,

    /** Reading files outside the bound workspace / the effective read scope. */
    FILE_READ_EXTERNAL,

    /**
     * Creating, overwriting, editing, appending, moving, renaming, deleting or changing
     * permissions/links of files inside the workspace — matched by EFFECT, never by tool name
     * alone (a Shell redirect, `tee` or a script write is the same key).
     */
    FILE_MUTATION_WORKSPACE,

    /**
     * The same mutation effects outside the workspace. A copy/check also carries the source
     * READ key; a move additionally carries the source mutation; a download-save carries the
     * target mutation.
     */
    FILE_MUTATION_EXTERNAL,

    /**
     * Server-side business side effects: posting, submitting, creating, modifying, deleting.
     * Classified by the trusted adapter contract — NEVER by the HTTP method (a GET can
     * delete). Requires the actual networking capability; grants no local file rights.
     */
    REMOTE_BUSINESS_MUTATION,

    /**
     * Device, settings or application-interaction modifications; still requires the actual
     * system capability.
     */
    DEVICE_SYSTEM_MUTATION,

    /**
     * Shell/PRoot/script execution admission. Allowing execution never overrides the read,
     * mutation, network or device rules above; a command whose effects cannot be fully
     * determined carries the affected keys as undetermined effects.
     */
    COMMAND_EXECUTION,
}
