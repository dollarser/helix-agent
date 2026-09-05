package com.helix.runtime.cli.app

data class CliRuntimeLock(
    val lockVersion: Int,
    val abi: String,
    val artifacts: List<CliArtifact>,
)

data class CliArtifact(
    val id: String,
    val version: String,
    val kind: String,
    val bundled: Boolean,
    val url: String,
    val size: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
    val termsUrl: String,
)
