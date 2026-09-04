package com.helix.runtime.proot.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Strict codec for the installed-copy records (HXA-080; architecture doc section 6.3):
 * `manifest.json` (per install id) and the activation pointers `active.json` /
 * `rollback.json`.
 *
 * Fail-closed: structural violations AND integrity violations (a tampered embedded lock,
 * an unknown schema version, a malformed install id) throw
 * [RuntimeLockSchemaException].
 */
object RuntimeManifestCodec {
    /** The only supported installed-record schema version. */
    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    /** Install id form: `inst_` + 12 lowercase hex chars (mirrors the QuickJS `js_` convention). */
    const val INSTALL_ID_PREFIX: String = "inst_"
    const val INSTALL_ID_HEX_LENGTH: Int = 12

    private val json = Json { isLenient = false }
    private val installIdPattern =
        Regex("${INSTALL_ID_PREFIX}[0-9a-f]{${INSTALL_ID_HEX_LENGTH}}")

    fun checkInstallId(installId: String) {
        if (!installIdPattern.matches(installId)) {
            throw RuntimeLockSchemaException("malformed install id: $installId")
        }
    }

    fun parseManifest(text: String): RuntimeInstallManifest {
        val root = JsonSchemaSupport.parseDocument(text, "manifest")
        val what = "manifest"
        val smokeKeyPresent = "smoke" in root
        JsonSchemaSupport.checkKeys(
            root,
            setOf("schemaVersion", "installId", "abi", "installedAtEpochMs", "lockSha256", "lock")
                .let { if (smokeKeyPresent) it + "smoke" else it },
            what,
        )
        checkSchemaVersion(root, what)
        val installId = JsonSchemaSupport.string(root, "installId", what)
        checkInstallId(installId)
        val abi = RuntimeAbi.fromWire(JsonSchemaSupport.string(root, "abi", what))
        val installedAtEpochMs =
            JsonSchemaSupport.long(
                root,
                "installedAtEpochMs",
                what,
                minimum = 0L,
                maximum = Long.MAX_VALUE,
            )
        val (lock, lockSha256) = verifyEmbeddedLock(root, what, abi)
        val smoke =
            if (smokeKeyPresent) {
                parseSmoke(JsonSchemaSupport.objectValue(root, "smoke", what))
            } else {
                null
            }
        return RuntimeInstallManifest(
            SUPPORTED_SCHEMA_VERSION,
            installId,
            abi,
            installedAtEpochMs,
            lockSha256,
            lock,
            smoke,
        )
    }

    private fun checkSchemaVersion(
        root: JsonObject,
        what: String,
    ) {
        val schemaVersion = JsonSchemaSupport.int(root, "schemaVersion", what)
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw RuntimeLockSchemaException(
                "$what.schemaVersion is $schemaVersion; only $SUPPORTED_SCHEMA_VERSION is supported",
            )
        }
    }

    /**
     * Parses the embedded lock and proves its integrity: the `lockSha256` field must be a
     * canonical hash AND match the embedded document, and the manifest ABI must agree with
     * the lock ABI. A tampered embedded lock is a schema violation, not data.
     */
    private fun verifyEmbeddedLock(
        root: JsonObject,
        what: String,
        abi: RuntimeAbi,
    ): Pair<RuntimeLock, String> {
        val lock =
            RuntimeLockCodec.parse(
                json.encodeToString(
                    JsonObject.serializer(),
                    JsonSchemaSupport.objectValue(root, "lock", what),
                ),
            )
        if (abi != lock.abi) {
            throw RuntimeLockSchemaException(
                "$what.abi ${abi.wire} != embedded lock ABI ${lock.abi.wire}",
            )
        }
        val lockSha256 =
            JsonSchemaSupport.sha256(
                JsonSchemaSupport.string(root, "lockSha256", what),
                what,
                "lockSha256",
            )
        if (lockSha256 != RuntimeLockCodec.sha256Hex(lock)) {
            throw RuntimeLockSchemaException("$what.lockSha256 does not match the embedded lock")
        }
        return lock to lockSha256
    }

    private fun parseSmoke(smoke: JsonObject): RuntimeSmokeResult {
        val what = "manifest.smoke"
        JsonSchemaSupport.checkKeys(
            smoke,
            setOf("status", "checkedAtEpochMs", "failures"),
            what,
        )
        val status = SmokeStatus.fromWire(JsonSchemaSupport.string(smoke, "status", what))
        val checkedAtEpochMs =
            JsonSchemaSupport.long(
                smoke,
                "checkedAtEpochMs",
                what,
                minimum = 0L,
                maximum = Long.MAX_VALUE,
            )
        val failures =
            JsonSchemaSupport.array(smoke, "failures", what).map { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw RuntimeLockSchemaException("$what.failures[] must be a string")
            }
        if ((status == SmokeStatus.PASSED) == failures.isNotEmpty()) {
            throw RuntimeLockSchemaException(
                "$what: PASSED requires no failures; FAILED requires at least one",
            )
        }
        return RuntimeSmokeResult(status, checkedAtEpochMs, failures)
    }

    /** Canonical encoding (fixed key order); [RuntimeInstallManifest.smoke] is emitted only when present. */
    fun encodeManifest(manifest: RuntimeInstallManifest): String {
        val obj =
            buildJsonObject {
                put("schemaVersion", manifest.schemaVersion)
                put("installId", manifest.installId)
                put("abi", manifest.abi.wire)
                put("installedAtEpochMs", manifest.installedAtEpochMs)
                put("lockSha256", manifest.lockSha256)
                put("lock", json.parseToJsonElement(RuntimeLockCodec.encode(manifest.lock)))
                manifest.smoke?.let { smoke ->
                    put(
                        "smoke",
                        buildJsonObject {
                            put("status", smoke.status.wire)
                            put("checkedAtEpochMs", smoke.checkedAtEpochMs)
                            put(
                                "failures",
                                buildJsonArray {
                                    smoke.failures.forEach { add(JsonPrimitive(it)) }
                                },
                            )
                        },
                    )
                }
            }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun parseActivation(text: String): RuntimeActivation {
        val root = JsonSchemaSupport.parseDocument(text, "activation")
        val what = "activation"
        JsonSchemaSupport.checkKeys(
            root,
            setOf("schemaVersion", "installId", "activatedAtEpochMs"),
            what,
        )
        val schemaVersion = JsonSchemaSupport.int(root, "schemaVersion", what)
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw RuntimeLockSchemaException(
                "activation.schemaVersion is $schemaVersion; only $SUPPORTED_SCHEMA_VERSION is supported",
            )
        }
        val installId = JsonSchemaSupport.string(root, "installId", what)
        checkInstallId(installId)
        val activatedAtEpochMs =
            JsonSchemaSupport.long(
                root,
                "activatedAtEpochMs",
                what,
                minimum = 0L,
                maximum = Long.MAX_VALUE,
            )
        return RuntimeActivation(schemaVersion, installId, activatedAtEpochMs)
    }

    fun encodeActivation(activation: RuntimeActivation): String {
        val obj =
            buildJsonObject {
                put("schemaVersion", activation.schemaVersion)
                put("installId", activation.installId)
                put("activatedAtEpochMs", activation.activatedAtEpochMs)
            }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
