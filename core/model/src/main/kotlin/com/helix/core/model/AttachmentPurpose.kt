package com.helix.core.model

/**
 * The role an attachment plays in the message it is bound to (ADR-0014, HXA-049). A closed,
 * stable, non-sensitive descriptor persisted in `message_attachments.purpose`.
 *
 * This is deliberately NOT the closed classification (text kind / unsupported category): that is
 * re-derived from the hash-verified bytes at materialization and is never a column, so a tampered
 * or stale `purpose` cannot change what the model actually reads. [REFERENCE] is the only role in
 * this milestone — every materializable attachment is reference content the user wants the model to
 * read; unsupported types are rejected before binding and never reach the relation.
 */
object AttachmentPurpose {
    const val REFERENCE = "REFERENCE"
}
