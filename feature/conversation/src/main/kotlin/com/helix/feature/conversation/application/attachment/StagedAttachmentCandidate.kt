package com.helix.feature.conversation.application.attachment

/** Immutable composer identity and display facts. Files and storage rows stay behind the attachment port. */
@Suppress("LongParameterList") // Raw and normalized identities are distinct, immutable snapshots.
data class StagedAttachmentCandidate(
    val sessionId: String,
    val artifactId: String,
    val fileName: String,
    val sizeBytes: Long,
    val boundSha256: String,
    /** Scope-relative model reference, never an absolute filesystem path. */
    val relativePath: String,
    val normalizedArtifactId: String? = null,
    val normalizedSha256: String? = null,
    val normalizedWidth: Int = 0,
    val normalizedHeight: Int = 0,
    val normalizedMediaType: String? = null,
    /** A failed normalization keeps the local attachment visible but cannot authorize a send. */
    val imageSendError: String? = null,
)
