package com.helix.app

internal object DistributionModuleRegistry {
    const val EDITION: String = "consumer"

    /**
     * Developer-only module markers compiled into this flavor (none in the consumer build).
     * The property is the variant probe anchor: `verify-variant-boundaries.sh` greps the APK
     * dex for the markers' `HELIX_DEVELOPER_ONLY_*` constants, which must be absent here.
     */
    val developerOnlyMarkers: List<Class<*>> = emptyList()
}
