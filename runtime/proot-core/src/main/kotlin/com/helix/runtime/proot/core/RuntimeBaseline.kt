package com.helix.runtime.proot.core

/**
 * The product baseline for the first PRoot Runtime release (architecture doc
 * local-code-execution section 6.3 基线建议): one `proot` binary, one `alpine-rootfs`
 * archive, and the fixed base tool set inside that archive.
 *
 * These are product rules layered on the generic [RuntimeLock] schema: [violations] is the
 * single check the build-time asset gate (HXA-081), the on-device installer (HXA-082) and
 * the handshake (HXA-083) all run via [requireBaseline], so "which components the baseline
 * demands" can never drift between surfaces.
 */
object RuntimeBaseline {
    const val PROOT_COMPONENT_ID: String = "proot"
    const val ROOTFS_COMPONENT_ID: String = "alpine-rootfs"

    /** The fixed base tool set baked into the rootfs (section 6.3: bash/git/python3/nodejs/ripgrep). */
    val REQUIRED_ROOTFS_PACKAGES: Set<String> =
        setOf("bash", "git", "python3", "nodejs", "ripgrep")

    /** Empty list = the lock describes the baseline. */
    fun violations(lock: RuntimeLock): List<String> {
        val violations = mutableListOf<String>()
        if (lock.component(PROOT_COMPONENT_ID) == null) {
            violations += "missing required component: $PROOT_COMPONENT_ID"
        }
        val rootfs = lock.component(ROOTFS_COMPONENT_ID)
        if (rootfs == null) {
            violations += "missing required component: $ROOTFS_COMPONENT_ID"
        } else {
            REQUIRED_ROOTFS_PACKAGES
                .filterNot { name -> rootfs.packages.any { it.name == name } }
                .forEach { name -> violations += "rootfs is missing required package: $name" }
        }
        // Every component must be built for the lock's ABI (a mixed-ABI lock is not
        // installable on one device).
        lock.components
            .filter { it.abi != lock.abi }
            .forEach { violations += "component ${it.id} ABI ${it.abi.wire} != lock ABI ${lock.abi.wire}" }
        return violations
    }
}
