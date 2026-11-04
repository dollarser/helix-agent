package com.helix.feature.files

import java.nio.file.Path

/**
 * The SAF tree scope access the app's file manager consumes (HXA-057). Bundles:
 * - [service]: the grant registry + real-time re-verification ([SafTreeScopeService]);
 * - [reader]: the read-only browse backend ([SafTreeReader]);
 * - [shareDir]: an APP-PRIVATE directory where a SAF document is staged (chunk-copied) before the
 *   share action mints a `content://` URI from it (doc 10: the real path is never rendered; the
 *   share flow hands a transient FileProvider URI to another app).
 *
 * The file manager treats every SAF operation as governed: it re-verifies the grant through
 * [SafTreeScopeService.resolve] before each browse/read, and the browse goes through [reader]
 * (the platform adapter), never a direct external-file access (AGENTS: 禁止 UI/DAO 直接操作外部
 * 文件). This access object provides the read path. Manual mutations use the separate governed backend;
 * a read-only grant still fails write re-verification.
 */
class SafTreeScopeAccess(
    val service: SafTreeScopeService,
    val reader: SafTreeReader,
    val shareDir: Path,
)
