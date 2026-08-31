package com.helix.core.policy

/**
 * Where the data a call reads or sends comes from (architecture doc section 8: "数据来自
 * Workspace、SAF、All-files、浏览器、Accessibility、MCP 还是 Root").
 *
 * The origin sets a floor on the egress [DataSensitivity] when the call also egresses: browser
 * page content and accessibility content are high-sensitivity by definition (provider doc
 * section 2.6), no matter how the caller labels them. File origins do not force a floor — the
 * caller knows whether it is sending a file body (SENSITIVE) or plain metadata (NORMAL).
 */
enum class DataOrigin {
    /** Session-private workspace (the default scope). */
    WORKSPACE,

    /** User-authorized SAF document tree. */
    SAF,

    /** All-files-access root directories. */
    ALL_FILES,

    /** Browser page content — egressing this is at least SENSITIVE. */
    BROWSER,

    /** Accessibility content — egressing this is at least SENSITIVE. */
    ACCESSIBILITY,

    /** Data originating from an MCP server (untrusted by default). */
    MCP,

    /** Data originating under Root. */
    ROOT,

    /** App-local data not covered by a more specific origin. */
    LOCAL,

    /** Data fetched from the network. */
    NETWORK,
}

/**
 * The sensitivity floor an origin imposes on egressing its data (see [DataOrigin] KDoc).
 */
internal fun DataOrigin.sensitivityFloor(): DataSensitivity =
    when (this) {
        DataOrigin.BROWSER,
        DataOrigin.ACCESSIBILITY,
        -> DataSensitivity.SENSITIVE

        else -> DataSensitivity.NORMAL
    }
