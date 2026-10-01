package com.helix.provider.api

/** Per endpoint/model context policy; independent of cumulative Turn/Goal budgets. */
data class ProviderContextSettings(
    val manualWindow: Long? = null,
    val serverWindow: Long? = null,
    val autoCompact: Boolean = true,
    val triggerPercent: Int = 80,
) {
    init {
        require(manualWindow == null || manualWindow in MIN_WINDOW..MAX_WINDOW)
        require(serverWindow == null || serverWindow in MIN_WINDOW..MAX_WINDOW)
        require(triggerPercent in 10..95)
    }

    val window: Long get() = listOfNotNull(manualWindow, serverWindow).minOrNull() ?: DEFAULT_WINDOW

    val windowSource: String get() =
        when {
            serverWindow != null && (manualWindow == null || serverWindow <= manualWindow) -> "provider"
            manualWindow != null -> "manual"
            else -> "fallback"
        }

    fun withDetectedWindow(detected: Long?): ProviderContextSettings =
        if (detected == null) this else copy(serverWindow = detected)

    companion object {
        const val DEFAULT_WINDOW = 200_000L
        const val MIN_WINDOW = 1024L
        const val MAX_WINDOW = com.helix.provider.api.ProviderCapabilities.MAX_CONTEXT_BOUND
    }
}
