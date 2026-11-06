package com.helix.extensions.mobileuse.tools

/** Distinguish a missing object from a platform service refusal before recommending recovery. */
internal fun missingTargetHint(status: String): String? =
    if (status == "TARGET_UNAVAILABLE") {
        "No actionable app or window was found. Open the intended authorized app with ui.launch, " +
            "then observe again. If the app is opening, wait briefly. " +
            "This does not mean the backend lacks the capability."
    } else if (status == "PRIVILEGED_SERVICE_UNAVAILABLE") {
        "The privileged process cannot access Android's window or accessibility service. " +
            "No input was dispatched. Root UID alone does not prove system-service access. " +
            "Check the Root manager's SELinux/service configuration or the selected shell backend, " +
            "then reconnect and observe again. Do not repeat input or silently switch backends."
    } else {
        null
    }
