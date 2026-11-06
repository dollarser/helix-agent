package com.helix.extensions.mobileuse.automation

/** Select before dispatch only. Bound observations never migrate after an effect or transport loss. */
class AutomationBackendSelection(
    private val root: AutomationPrivilegedBackend?,
    private val shizuku: AutomationPrivilegedBackend?,
    private val adb: () -> AutomationPrivilegedBackend? = { null },
) {
    fun select(operation: AutomationDeviceOperation): AutomationPrivilegedBackend? =
        listOfNotNull(root, adb(), shizuku).firstOrNull {
            operation in it.deviceOperations && AutomationDeviceOperation.OBSERVE in it.deviceOperations &&
                it.state() == AutomationBackendState.READY
        }

    fun preferred(operation: AutomationDeviceOperation): AutomationClickBackend? = select(operation)?.let(::kind)

    fun kind(backend: AutomationPrivilegedBackend): AutomationClickBackend =
        when (backend) {
            root -> AutomationClickBackend.ROOT
            shizuku -> AutomationClickBackend.SHIZUKU
            else -> AutomationClickBackend.ADB
        }
}
