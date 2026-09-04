package com.helix.runtime.proot.core

import java.io.File

/**
 * Read-only queries over an installed runtime's state (HXA-087 update detection):
 * separated from [RootFsInstaller] (the write side) so the update-state decision
 * never sits next to the install/rollback mutations.
 */
object RuntimeInstallQueries {
    /**
     * The active install's embedded-lock fingerprint: reads
     * `runtime/<active-install-id>/manifest.json` and returns its verified
     * `lockSha256`. Null when there is no active install or no manifest; a CORRUPT
     * manifest throws (same convention as [RootFsInstaller.currentActive]) — the
     * UI maps that to a stable refusal, never to "up to date".
     */
    fun activeLockSha256(runtimeRoot: File): String? {
        val active = RootFsInstaller.currentActive(runtimeRoot) ?: return null
        val manifestFile = File(runtimeRoot, "${active.installId}/manifest.json")
        return if (manifestFile.isFile) {
            RuntimeManifestCodec.parseManifest(manifestFile.readText()).lockSha256
        } else {
            null
        }
    }
}
