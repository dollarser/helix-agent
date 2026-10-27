package com.helix.runtime.proot.core

import java.io.File

/** Installation facts only: reading these never starts a process or grants execution permission. */
enum class RuntimeInstallationState {
    NOT_PREPARED,
    PREPARED,
    UPDATE_REQUIRED,
    DAMAGED,
    RESET_REQUIRED,
}

data class RuntimeInstallation(
    val state: RuntimeInstallationState,
    val manifest: RuntimeInstallManifest? = null,
    val diagnostic: String? = null,
) {
    companion object {
        /** Explicit structural diagnostics; persists their result on the original active installation. */
        fun check(
            runtimeRoot: File,
            bundledLock: RuntimeLock,
            facts: InstallFacts,
        ): RuntimeSmokeResult {
            val installed = inspect(runtimeRoot, bundledLock)
            val manifest = installed.manifest ?: error(installed.diagnostic ?: "Environment is not prepared")
            require(installed.state == RuntimeInstallationState.PREPARED) { "Environment requires repair or update" }
            val directory = File(runtimeRoot, manifest.installId)
            val result = InstallSmoke(manifest.lock, facts).run(directory)
            InstallState.atomicWrite(
                File(directory, "manifest.json"),
                RuntimeManifestCodec.encodeManifest(manifest.copy(smoke = result)),
            )
            return result
        }

        private fun readRecord(
            file: File,
            limit: Int,
        ): String =
            file.reader().use { reader ->
                val buffer = CharArray(8192)
                val text = StringBuilder()
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    require(text.length + read <= limit) { "Installation record is too large" }
                    text.append(buffer, 0, read)
                }
                text.toString()
            }

        /** Check the active copy, not merely the resources bundled in the APK. */
        fun inspect(
            runtimeRoot: File,
            bundledLock: RuntimeLock,
        ): RuntimeInstallation =
            runCatching {
                val pointer = File(runtimeRoot, "state/active.json")
                if (!pointer.exists()) {
                    val children = if (runtimeRoot.exists()) requireNotNull(runtimeRoot.listFiles()) else emptyArray()
                    if (children.any { it.name.startsWith("inst_") }) {
                        return RuntimeInstallation(
                            RuntimeInstallationState.RESET_REQUIRED,
                            diagnostic = "Installed environment has lost its activation record",
                        )
                    }
                    return RuntimeInstallation(RuntimeInstallationState.NOT_PREPARED)
                }
                val active =
                    runCatching { RuntimeManifestCodec.parseActivation(readRecord(pointer, 8192)) }
                        .getOrElse {
                            return RuntimeInstallation(RuntimeInstallationState.RESET_REQUIRED, diagnostic = it.message)
                        }
                val directory = File(runtimeRoot, active.installId)
                require(directory.canonicalFile.parentFile == runtimeRoot.canonicalFile) { "Invalid installation path" }
                val file = File(directory, "manifest.json")
                val manifest = RuntimeManifestCodec.parseManifest(readRecord(file, 2 * 1024 * 1024))
                require(manifest.installId == active.installId) { "Installation identity mismatch" }
                require(manifest.smoke?.status == SmokeStatus.PASSED) { "Installation checks did not pass" }
                if (!manifest.lock.sameInstalledArtifacts(bundledLock)) {
                    RuntimeInstallation(RuntimeInstallationState.UPDATE_REQUIRED, manifest)
                } else {
                    val failures = InstallSmoke(manifest.lock, InstallFacts(4096, 0)).checkRequiredFiles(directory)
                    require(failures.isEmpty()) { failures.joinToString("; ") }
                    RuntimeInstallation(RuntimeInstallationState.PREPARED, manifest)
                }
            }.getOrElse {
                RuntimeInstallation(RuntimeInstallationState.DAMAGED, diagnostic = it.message)
            }
    }
}
