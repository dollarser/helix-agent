package com.helix.app.connector

/**
 * UI representation of a Connector for session capability configuration.
 * Adheres to ADR-CONNECTORS-003: distinguishing installation vs per-session selection.
 */
data class SessionConnectorItemUi(
    val connectorId: String,
    val packageId: String,
    val displayName: String,
    val author: String? = null,
    val version: String,
    val isEnabledInSession: Boolean,
    val isReady: Boolean,
    val statusMessage: String? = null,
    val skillCount: Int = 0,
    val mcpToolCount: Int = 0,
    val hasSharedRemainingSource: Boolean = false,
)

/**
 * Immutable UI state for the session capability panel.
 */
data class SessionCapabilityUiState(
    val sessionId: String,
    val connectors: List<SessionConnectorItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Pure evaluator for effective tool availability in a session.
 * Enforces ADR-CONNECTORS-003 §2:
 * 1. Package must be installed and enabled in session.
 * 2. Component must be ready.
 * 3. Tool must not be globally or explicitly disabled.
 */
object SessionConnectorAvailabilityEvaluator {
    data class InstalledPackageSpec(
        val connectorId: String,
        val isReady: Boolean,
        val providedTools: Set<String>,
        val providedSkills: Set<String>,
    )

    /**
     * Computes the set of available tool identifiers for a session.
     */
    fun computeAvailableTools(
        sessionEnabledConnectorIds: Set<String>,
        packages: List<InstalledPackageSpec>,
        globallyDisabledTools: Set<String>,
    ): Set<String> {
        val available = mutableSetOf<String>()

        for (pkg in packages) {
            // Must be enabled in this specific session and ready
            if (pkg.connectorId in sessionEnabledConnectorIds && pkg.isReady) {
                for (tool in pkg.providedTools) {
                    if (tool !in globallyDisabledTools) {
                        available += tool
                    }
                }
            }
        }

        return available
    }

    /**
     * Checks if a skill still has an alternative source if one package is disabled.
     */
    fun hasRemainingSkillSource(
        skillKey: String,
        disablingConnectorId: String,
        sessionEnabledConnectorIds: Set<String>,
        packages: List<InstalledPackageSpec>,
        hasIndependentUserInstall: Boolean = false,
    ): Boolean {
        if (hasIndependentUserInstall) return true

        val otherEnabledPackages =
            packages.filter {
                it.connectorId != disablingConnectorId &&
                    it.connectorId in sessionEnabledConnectorIds &&
                    it.isReady
            }

        return otherEnabledPackages.any { skillKey in it.providedSkills }
    }
}
