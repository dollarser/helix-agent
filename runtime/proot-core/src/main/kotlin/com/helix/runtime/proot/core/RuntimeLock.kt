package com.helix.runtime.proot.core

/**
 * The PRoot Runtime build-time asset lock (HXA-080; architecture doc
 * local-code-execution section 6.3/6.4).
 *
 * `runtime-lock.json` is the 唯一版本真相 (single version truth) for every native/runtime
 * asset that ships inside the signed `com.helix.runtime.proot` APK: PRoot, the Alpine
 * minirootfs archive (with its pre-installed package set) and any future component. URL,
 * version, size, SHA-256, license and source (repository/ref/patches) are ALL read from the
 * parsed lock at build time (HXA-081 asset fetch/verify), install time (HXA-082) and display
 * time (license page, HXA-087) — no version, hash or URL may be hardcoded anywhere else.
 *
 * The document is a strict, closed schema (see [RuntimeLockCodec]): unknown keys, missing
 * fields, malformed values and cross-field violations all throw
 * [RuntimeLockSchemaException] (fail-closed).
 *
 * [lockVersion] is the schema version, NOT a runtime release version. Unsupported
 * (newer or older) schema versions are rejected: a lock the app cannot parse is not
 * version truth.
 */
data class RuntimeLock(
    val lockVersion: Int,
    val abi: RuntimeAbi,
    val components: List<RuntimeComponent>,
) {
    init {
        require(components.isNotEmpty()) { "runtime lock must contain at least one component" }
        require(components.map { it.id }.toSet().size == components.size) {
            "runtime lock contains duplicate component ids"
        }
    }

    /** The locked component with [id], or null (lookup, not a parse path). */
    fun component(id: String): RuntimeComponent? = components.firstOrNull { it.id == id }
}

/**
 * One locked runtime asset: a native binary or an archive (e.g. the PRoot binary, the
 * Alpine minirootfs tarball).
 *
 * [url]/[size]/[sha256] describe the EXACT build-time artifact; [license] and [source]
 * carry the redistribution obligations (GPL-2.0 PRoot, per-package Alpine licenses, ...);
 * [packages] is non-empty only for archive components whose content is a package set
 * (the rootfs: bash/git/python3/nodejs/ripgrep, architecture doc section 6.3).
 */
data class RuntimeComponent(
    val id: String,
    val version: String,
    val abi: RuntimeAbi,
    val url: String,
    val size: Long,
    val sha256: String,
    val license: RuntimeLicense,
    val source: RuntimeSource,
    val packages: List<RuntimePackageInfo> = emptyList(),
) {
    init {
        require(packages.map { it.name }.toSet().size == packages.size) {
            "component $id lists duplicate packages"
        }
        require(
            source.patches
                .map { it.path }
                .toSet()
                .size == source.patches.size,
        ) {
            "component $id lists duplicate patch paths"
        }
    }
}

/**
 * The license record of one locked component (redistribution obligation, architecture doc
 * section 9).
 *
 * [spdx] is the SPDX identifier of the component's license (e.g. `GPL-2.0-only`); [name]
 * is the human-readable license name for the in-app legal page; [textRef] is the relative
 * path of the full license text inside the Runtime APK's embedded `licenses/` directory
 * (relative, no traversal — the text itself ships in the APK so the legal page works
 * offline, HXA-087).
 */
data class RuntimeLicense(
    val spdx: String,
    val name: String,
    val textRef: String,
)

/**
 * Where a locked component came from (source availability obligation, architecture doc
 * section 9: 提供与二进制精确对应的源码获取方式).
 *
 * [repository] is the source repository URL; [ref] is the EXACT tag or commit the
 * component was built from (never a branch or `latest`); [patches] are the source patches
 * applied on top of [ref], each with its own URL/size/SHA-256 and a traversal-safe path
 * relative to the embedded `patches/` directory.
 */
data class RuntimeSource(
    val repository: String,
    val ref: String,
    val patches: List<RuntimePatch> = emptyList(),
)

/** A single source patch applied to a component's upstream source. */
data class RuntimePatch(
    val path: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

/**
 * One pre-installed package inside an archive component (the Alpine rootfs: bash, git,
 * python3, nodejs, ripgrep — architecture doc section 6.3). Version and license are
 * recorded per package because Alpine has no single covering license.
 */
data class RuntimePackageInfo(
    val name: String,
    val version: String,
    val licenseSpdx: String,
)
