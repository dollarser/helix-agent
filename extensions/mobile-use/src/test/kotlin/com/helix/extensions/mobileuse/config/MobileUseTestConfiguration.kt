package com.helix.extensions.mobileuse.config

/** Test host: selection is external to the plugin's configuration store. */
internal class MobileUseTestConfiguration(
    read: (String) -> List<String>,
    write: (String, List<String>) -> Unit,
    private val selections: MutableMap<String, String> = mutableMapOf(),
) {
    private val configuration = MobileUseGrantStore(read, write, selections::get)

    fun authorize(
        id: String,
        packages: Set<String>,
        whole: Boolean,
    ): MobileUseGrant {
        configuration.configureGlobal(packages, whole)
        selections.getOrPut(id) {
            java.util.UUID
                .randomUUID()
                .toString()
        }
        return checkNotNull(configuration.find(id))
    }

    fun find(id: String) = configuration.find(id)

    fun revoke(id: String) {
        selections.remove(id)
    }

    fun matches(
        id: String?,
        scope: String?,
    ) = configuration.matches(id, scope)
}
