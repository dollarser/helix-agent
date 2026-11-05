package com.helix.extensions.mobileuse.automation.backend

/** No cached fallback. Every retry must revalidate the original owner, scope and display. */
internal sealed interface BackgroundPreviewRead {
    data class Frame(
        val bytes: ByteArray,
    ) : BackgroundPreviewRead

    data object Transition : BackgroundPreviewRead

    data object Refused : BackgroundPreviewRead
}

@Suppress("ReturnCount") // A frame completes the read; a refusal must exit without waiting or retrying.
internal fun readBackgroundPreview(
    read: () -> BackgroundPreviewRead,
    pause: () -> Unit,
): ByteArray? {
    repeat(3) { attempt ->
        when (val result = read()) {
            is BackgroundPreviewRead.Frame -> return result.bytes
            BackgroundPreviewRead.Refused -> return null
            BackgroundPreviewRead.Transition -> if (attempt < 2) pause()
        }
    }
    return null
}
