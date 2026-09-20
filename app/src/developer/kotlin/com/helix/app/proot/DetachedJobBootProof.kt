package com.helix.app.proot

import android.content.Context
import android.provider.Settings
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.DetachedJobBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Reboot proves old local processes cannot still execute; wall clocks and elapsed uptime do not. */
internal object DetachedJobBootProof {
    fun reviewReason(current: Int?): String =
        if (current != null) {
            "JOB_REBOOT_REQUIRED: reboot the device, then collect the original Job; nothing will be replayed."
        } else {
            "JOB_REQUIRES_REVIEW: execution interrupted; original ownership remains retained."
        }

    fun current(context: Context): Int? =
        try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 }
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }

    fun provesStopped(
        original: Int?,
        current: Int?,
    ): Boolean = original != null && original >= 0 && current != null && current > original

    /** Older bindings can establish an observation now, but need a later reboot before release. */
    fun canSettle(
        storage: HelixStorage,
        binding: DetachedJobBinding,
        original: Int?,
        current: Int?,
    ): Boolean {
        if (original != null) return provesStopped(original, current)
        val id = "proot-boot-observed-${binding.toolCallId}"
        val event =
            try {
                storage.auditEvents.resolve(id)
            } catch (_: IllegalArgumentException) {
                null
            }
        val observed =
            if (event != null) {
                check(event.correlationId == binding.sessionId && event.type == "proot.job_boot_observed")
                val payload = Json.parseToJsonElement(event.redactedPayload).jsonObject
                check(payload.getValue("executionId").jsonPrimitive.content == binding.executionId)
                payload.getValue("bootCount").jsonPrimitive.intOrNull
            } else {
                if (current != null) {
                    storage.auditEvents.append(
                        id,
                        binding.sessionId,
                        "proot.job_boot_observed",
                        "platform",
                        buildJsonObject {
                            put("executionId", binding.executionId)
                            put("bootCount", current)
                        }.toString(),
                        System.currentTimeMillis(),
                    )
                }
                current
            }
        return provesStopped(observed, current)
    }
}
