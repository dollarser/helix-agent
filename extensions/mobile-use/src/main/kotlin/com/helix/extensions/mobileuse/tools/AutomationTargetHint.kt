package com.helix.extensions.mobileuse.tools

/** A missing object is not a missing permission or a permanently unsupported capability. */
internal fun missingTargetHint(status: String): String? =
    if (status == "TARGET_UNAVAILABLE") {
        "No actionable app or window was found. Open the intended authorized app with ui.launch, " +
            "then observe again. If the app is opening, wait briefly. " +
            "This does not mean the backend lacks the capability."
    } else {
        null
    }
