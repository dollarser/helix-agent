package com.helix.runtime.proot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * Strict codec for `runtime-lock.json` (HXA-080).
 *
 * [parse] is fail-closed: any structural, type, format, cross-field or baseline violation
 * throws [RuntimeLockSchemaException]. [encode] is canonical — fixed key order, no
 * insignificant whitespace — so [sha256Hex] over the encoding is a stable document
 * fingerprint the installer (HXA-082) and the license page (HXA-087) recompute to prove the
 * embedded lock is the one the build locked.
 */
object RuntimeLockCodec {
    /** The only supported lock schema version (fail-closed on anything else). */
    const val SUPPORTED_LOCK_VERSION: Int = 1

    private val json = Json { isLenient = false }

    fun parse(text: String): RuntimeLock {
        val root = JsonSchemaSupport.parseDocument(text, "runtime-lock")
        val what = "runtime-lock"
        JsonSchemaSupport.checkKeys(
            root,
            setOf("lockVersion", "abi", "components"),
            what,
        )
        val lockVersion = JsonSchemaSupport.int(root, "lockVersion", what)
        if (lockVersion != SUPPORTED_LOCK_VERSION) {
            throw RuntimeLockSchemaException(
                "runtime-lock.lockVersion is $lockVersion; only $SUPPORTED_LOCK_VERSION is supported",
            )
        }
        val abi =
            RuntimeAbi.fromWire(
                JsonSchemaSupport.string(root, "abi", what),
            )
        val components =
            JsonSchemaSupport.array(root, "components", what).map { element ->
                parseComponent(
                    element as? JsonObject
                        ?: throw RuntimeLockSchemaException("runtime-lock.components[] must be an object"),
                )
            }
        return RuntimeLock(lockVersion, abi, components)
    }

    private fun parseComponent(component: JsonObject): RuntimeComponent {
        val what = "component"
        JsonSchemaSupport.checkKeys(
            component,
            setOf("id", "version", "abi", "url", "size", "sha256", "license", "source", "packages"),
            what,
        )
        return RuntimeComponent(
            id = JsonSchemaSupport.string(component, "id", what),
            version = JsonSchemaSupport.string(component, "version", what),
            abi = RuntimeAbi.fromWire(JsonSchemaSupport.string(component, "abi", what)),
            url =
                JsonSchemaSupport.assetUrl(
                    JsonSchemaSupport.string(component, "url", what),
                    what,
                    "url",
                ),
            size =
                JsonSchemaSupport.long(
                    component,
                    "size",
                    what,
                    minimum = 1L,
                    maximum = JsonSchemaSupport.MAX_ARTIFACT_SIZE_BYTES,
                ),
            sha256 =
                JsonSchemaSupport.sha256(
                    JsonSchemaSupport.string(component, "sha256", what),
                    what,
                    "sha256",
                ),
            license = parseLicense(component, what),
            source = parseSource(component, what),
            packages = parsePackages(component, what),
        )
    }

    private fun parseLicense(
        component: JsonObject,
        what: String,
    ): RuntimeLicense {
        val obj = JsonSchemaSupport.objectValue(component, "license", what)
        val licenseWhat = "$what.license"
        JsonSchemaSupport.checkKeys(
            obj,
            setOf("spdx", "name", "textRef"),
            licenseWhat,
        )
        return RuntimeLicense(
            spdx = JsonSchemaSupport.string(obj, "spdx", licenseWhat),
            name = JsonSchemaSupport.string(obj, "name", licenseWhat),
            textRef =
                JsonSchemaSupport.embeddedRef(
                    JsonSchemaSupport.string(obj, "textRef", licenseWhat),
                    licenseWhat,
                    "textRef",
                ),
        )
    }

    private fun parseSource(
        component: JsonObject,
        what: String,
    ): RuntimeSource {
        val obj = JsonSchemaSupport.objectValue(component, "source", what)
        val sourceWhat = "$what.source"
        JsonSchemaSupport.checkKeys(
            obj,
            setOf("repository", "ref", "patches"),
            sourceWhat,
        )
        return RuntimeSource(
            repository =
                JsonSchemaSupport.assetUrl(
                    JsonSchemaSupport.string(obj, "repository", sourceWhat),
                    sourceWhat,
                    "repository",
                ),
            ref = JsonSchemaSupport.string(obj, "ref", sourceWhat),
            patches =
                JsonSchemaSupport.array(obj, "patches", sourceWhat).map { element ->
                    val patch =
                        element as? JsonObject
                            ?: throw RuntimeLockSchemaException("$sourceWhat.patches[] must be an object")
                    parsePatch(patch)
                },
        )
    }

    private fun parsePatch(patch: JsonObject): RuntimePatch {
        val what = "component.source.patch"
        JsonSchemaSupport.checkKeys(
            patch,
            setOf("path", "url", "size", "sha256"),
            what,
        )
        return RuntimePatch(
            path =
                JsonSchemaSupport.embeddedRef(
                    JsonSchemaSupport.string(patch, "path", what),
                    what,
                    "path",
                ),
            url = JsonSchemaSupport.assetUrl(JsonSchemaSupport.string(patch, "url", what), what, "url"),
            size =
                JsonSchemaSupport.long(
                    patch,
                    "size",
                    what,
                    minimum = 1L,
                    maximum = JsonSchemaSupport.MAX_ARTIFACT_SIZE_BYTES,
                ),
            sha256 = JsonSchemaSupport.sha256(JsonSchemaSupport.string(patch, "sha256", what), what, "sha256"),
        )
    }

    private fun parsePackages(
        component: JsonObject,
        what: String,
    ): List<RuntimePackageInfo> =
        JsonSchemaSupport.array(component, "packages", what).map { element ->
            val packageObj =
                element as? JsonObject
                    ?: throw RuntimeLockSchemaException("$what.packages[] must be an object")
            val packageWhat = "$what.package"
            JsonSchemaSupport.checkKeys(
                packageObj,
                setOf("name", "version", "licenseSpdx"),
                packageWhat,
            )
            RuntimePackageInfo(
                name = JsonSchemaSupport.string(packageObj, "name", packageWhat),
                version = JsonSchemaSupport.string(packageObj, "version", packageWhat),
                licenseSpdx = JsonSchemaSupport.string(packageObj, "licenseSpdx", packageWhat),
            )
        }

    /** Canonical encoding: fixed key order, no insignificant whitespace. */
    fun encode(lock: RuntimeLock): String {
        val obj =
            buildJsonObject {
                put("lockVersion", lock.lockVersion)
                put("abi", lock.abi.wire)
                put(
                    "components",
                    buildJsonArray {
                        lock.components.forEach { component -> add(encodeComponent(component)) }
                    },
                )
            }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun encodeComponent(component: RuntimeComponent) =
        buildJsonObject {
            put("id", component.id)
            put("version", component.version)
            put("abi", component.abi.wire)
            put("url", component.url)
            put("size", component.size)
            put("sha256", component.sha256)
            put(
                "license",
                buildJsonObject {
                    put("spdx", component.license.spdx)
                    put("name", component.license.name)
                    put("textRef", component.license.textRef)
                },
            )
            put(
                "source",
                buildJsonObject {
                    put("repository", component.source.repository)
                    put("ref", component.source.ref)
                    put(
                        "patches",
                        buildJsonArray {
                            component.source.patches.forEach { patch ->
                                add(
                                    buildJsonObject {
                                        put("path", patch.path)
                                        put("url", patch.url)
                                        put("size", patch.size)
                                        put("sha256", patch.sha256)
                                    },
                                )
                            }
                        },
                    )
                },
            )
            put(
                "packages",
                buildJsonArray {
                    component.packages.forEach { pkg ->
                        add(
                            buildJsonObject {
                                put("name", pkg.name)
                                put("version", pkg.version)
                                put("licenseSpdx", pkg.licenseSpdx)
                            },
                        )
                    }
                },
            )
        }

    /** Stable SHA-256 fingerprint of the canonical encoding (lowercase hex). */
    fun sha256Hex(lock: RuntimeLock): String = digestHex(encode(lock).encodeToByteArray())

    private fun digestHex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * The runtime lock parsed and baseline-validated: the ONLY version truth a build, installer
 * or display surface may consume. Baseline violations (architecture doc section 6.3
 * 基线) are schema-level: a lock without the required components/packages does not
 * describe the product baseline and is rejected.
 */
fun RuntimeLock.requireBaseline(): RuntimeLock {
    val violations = RuntimeBaseline.violations(this)
    if (violations.isNotEmpty()) {
        throw RuntimeLockSchemaException(
            "runtime-lock violates the product baseline: " + violations.joinToString("; "),
        )
    }
    return this
}
