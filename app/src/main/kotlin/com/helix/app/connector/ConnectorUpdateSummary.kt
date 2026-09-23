package com.helix.app.connector

/**
 * Summary of differences between current and updated Connector packages.
 * Enforces ADR-CONNECTORS-003 §3: concise updates without code diffs.
 */
data class ConnectorUpdateReport(
    val connectorId: String,
    val oldVersion: String,
    val newVersion: String,
    val changelog: String? = null,
    val addedSkills: List<String> = emptyList(),
    val removedSkills: List<String> = emptyList(),
    val changedEndpoints: List<String> = emptyList(),
    val requiresCredentialReconfiguration: Boolean = false,
)

/**
 * Pure evaluator of package update differences and credential preservation boundaries.
 */
object ConnectorUpdateDiffEvaluator {
    data class EndpointBinding(
        val endpointId: String,
        val originUrl: String,
        val authType: String,
        val oauthIssuer: String? = null,
        val oauthResource: String? = null,
    )

    data class PackageSnapshot(
        val version: String,
        val changelog: String? = null,
        val skillKeys: Set<String> = emptySet(),
        val endpoints: List<EndpointBinding> = emptyList(),
    )

    /**
     * Compares an existing installed package with an incoming update target.
     */
    fun evaluate(
        connectorId: String,
        current: PackageSnapshot,
        incoming: PackageSnapshot,
    ): ConnectorUpdateReport {
        val addedSkills = (incoming.skillKeys - current.skillKeys).sorted()
        val removedSkills = (current.skillKeys - incoming.skillKeys).sorted()

        val changedEndpoints = mutableListOf<String>()
        var requiresReconfiguration = false

        val currentMap = current.endpoints.associateBy { it.endpointId }
        for (newEp in incoming.endpoints) {
            val oldEp = currentMap[newEp.endpointId]
            if (oldEp == null) {
                changedEndpoints += newEp.endpointId
                requiresReconfiguration = true
            } else if (oldEp.originUrl != newEp.originUrl ||
                oldEp.authType != newEp.authType ||
                oldEp.oauthIssuer != newEp.oauthIssuer ||
                oldEp.oauthResource != newEp.oauthResource
            ) {
                // Endpoint binding changed — token must not be blindly copied (ADR-CONNECTORS-003 §3)
                changedEndpoints += newEp.endpointId
                requiresReconfiguration = true
            }
        }

        return ConnectorUpdateReport(
            connectorId = connectorId,
            oldVersion = current.version,
            newVersion = incoming.version,
            changelog = incoming.changelog,
            addedSkills = addedSkills,
            removedSkills = removedSkills,
            changedEndpoints = changedEndpoints,
            requiresCredentialReconfiguration = requiresReconfiguration,
        )
    }
}
