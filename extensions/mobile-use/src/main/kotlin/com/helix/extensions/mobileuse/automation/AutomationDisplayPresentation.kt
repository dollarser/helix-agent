package com.helix.extensions.mobileuse.automation

/** Disposable host facts only. Never a grant, task state, snapshot or execution target. */
enum class AutomationDisplayLifecycle {
    CREATING,
    ACTIVE,
    RELEASED,
    CONNECTION_LOST,
    RELEASE_UNKNOWN,
    UNAVAILABLE,
    UNKNOWN,
}

data class AutomationDisplayPresentation(
    val sessionId: String,
    val turnId: String,
    val lifecycle: AutomationDisplayLifecycle,
    val generation: Long,
    val lastObservedPackage: String? = null,
)
