package com.helix.extensions.mobileuse.automation

enum class AutomationClickBackend { ROOT, SHIZUKU, ACCESSIBILITY }

/** Selection precedes execution. A failure is never an instruction to replay on another backend. */
fun preferredAutomationClickBackend(
    root: AutomationBackendState,
    shizuku: AutomationBackendState,
): AutomationClickBackend =
    when {
        root == AutomationBackendState.READY -> AutomationClickBackend.ROOT
        shizuku == AutomationBackendState.READY -> AutomationClickBackend.SHIZUKU
        else -> AutomationClickBackend.ACCESSIBILITY
    }
