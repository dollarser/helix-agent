package com.helix.tools.automation

/** Once a platform call is entered, an exception is not proof that the target was unchanged. */
internal fun performPlatformAutomationAction(action: () -> Boolean): AutomationActionResult =
    try {
        AutomationActionResult(
            if (action()) AutomationActionStatus.SUCCEEDED else AutomationActionStatus.ACTION_FAILED,
        )
    } catch (_: RuntimeException) {
        AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
    }
