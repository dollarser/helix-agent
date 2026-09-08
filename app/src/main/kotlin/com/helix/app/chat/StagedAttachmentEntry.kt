package com.helix.app.chat

import java.nio.file.Path

/** The in-memory facts of one staged attachment (internal; the UI sees [PendingAttachmentUi]). */
@Suppress("LongParameterList") // one distinct staged fact per parameter (raw + HXA-055 normalized facts)
internal class StagedAttachmentEntry(
    /** The session that staged this entry — an in-flight switch/close drops it (ADR-0014 §5). */
    val sessionId: String,
    val artifactId: String,
    val fileName: String,
    val sizeBytes: Long,
    val boundSha256: String,
    /** The scope-relative workspace path the model chunk-reads the full content through. */
    val relativePath: String,
    /** The real workspace path — hashing/probing only, never exposed. */
    val file: Path,
    /** HXA-055 image facts (null/0 for text): the registered id of the NORMALIZED artifact. */
    val normalizedArtifactId: String? = null,
    /** The bound SHA-256 of the normalized artifact (re-verified at send, retry and restore). */
    val normalizedSha256: String? = null,
    /** The real path of the normalized artifact — hashing only, never exposed. */
    val normalizedFile: Path? = null,
    val normalizedWidth: Int = 0,
    val normalizedHeight: Int = 0,
    val normalizedMediaType: String? = null,
    /**
     * Set when the on-device normalization FAILED at staging (HXA-055, ADR-0014 §4): the raw
     * artifact stays local (save/preview still possible) but the send is blocked with this
     * actionable, user-visible reason — never a raw-base64 fallback.
     */
    val imageSendError: String? = null,
)
