package com.helix.extensions.mobileuse.automation

/** An existing display stays with its original provider, including through provider failure. */
internal class BackgroundBackendBindings(
    private val root: AutomationPrivilegedBackend?,
    private val shizuku: AutomationPrivilegedBackend?,
) {
    private val bindings = mutableMapOf<String, AutomationPrivilegedBackend>()

    fun available(): AutomationPrivilegedBackend? =
        listOfNotNull(root, shizuku).firstOrNull { it.state() == AutomationBackendState.READY }

    @Synchronized
    fun forKey(key: String): AutomationPrivilegedBackend? = bindings[key] ?: available()?.also { bindings[key] = it }

    @Synchronized
    fun forget(key: String) {
        bindings.remove(key)
    }
}
