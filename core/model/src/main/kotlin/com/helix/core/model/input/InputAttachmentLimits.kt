package com.helix.core.model.input

/** User input limits shared by staging, draft editing and submission admission. */
object InputAttachmentLimits {
    /** Bounded UTF-8 prefix shared by file probing and model-visible attachment text. */
    const val MAX_INLINE_TEXT_BYTES: Int = 8 * 1024

    /** ADR-0014: at most four attachments per message. */
    const val MAX_ATTACHMENTS_PER_MESSAGE: Int = 4
}
