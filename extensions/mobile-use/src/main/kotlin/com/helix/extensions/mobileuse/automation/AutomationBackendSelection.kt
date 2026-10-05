package com.helix.extensions.mobileuse.automation

/** Select before dispatch only. Bound observations never migrate after an effect or transport loss. */
class AutomationBackendSelection(
    private val root: AutomationPrivilegedBackend?,
    private val shizuku: AutomationPrivilegedBackend?,
) {
    fun select(operation: AutomationDeviceOperation): AutomationPrivilegedBackend? =
        listOfNotNull(root, shizuku).firstOrNull {
            operation in it.deviceOperations && AutomationDeviceOperation.OBSERVE in it.deviceOperations &&
                it.state() == AutomationBackendState.READY
        }

    fun preferred(operation: AutomationDeviceOperation): AutomationClickBackend? =
        select(operation)?.let { if (it === root) AutomationClickBackend.ROOT else AutomationClickBackend.SHIZUKU }
}
