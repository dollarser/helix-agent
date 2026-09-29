package com.helix.core.model

/**
 * The role an attachment plays in the message it is bound to (ADR-0014, HXA-049). A closed,
 * stable, non-sensitive descriptor persisted in `message_attachments.purpose`.
 *
 * This is deliberately NOT the closed classification (text kind / unsupported category): that is
 * re-derived from the hash-verified bytes at materialization and is never a column, so a tampered
 * or stale `purpose` cannot change what the model actually reads. User references and trusted tool
 * observations have separate provenance and egress rules; neither is execution authority.
 */
object AttachmentPurpose {
    const val REFERENCE = "REFERENCE"
    const val TOOL_OBSERVATION = "TOOL_OBSERVATION"
}
