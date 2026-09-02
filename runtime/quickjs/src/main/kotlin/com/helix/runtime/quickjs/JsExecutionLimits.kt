package com.helix.runtime.quickjs

import android.os.Parcel
import android.os.Parcelable

/**
 * HXA-051 per-execution limits (architecture doc local-code-execution §3.1 request type,
 * §4.1 default limits).
 *
 * Defaults are the §4.1 table values and cannot be raised by the model; user settings may
 * lower them. [validate] enforces the bounded ranges on both the client (pre-bind
 * rejection) and the service (defensive re-check) so the same pure rules are JVM-tested.
 */
data class JsExecutionLimits(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val memoryBytes: Long = DEFAULT_MEMORY_BYTES,
    val maxSourceBytes: Int = DEFAULT_MAX_SOURCE_BYTES,
    val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
) : Parcelable {
    /**
     * Returns this instance when every field is within its bounded range; throws
     * [IllegalArgumentException] with a stable reason otherwise.
     */
    fun validate(): JsExecutionLimits =
        apply {
            require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
                "timeoutMs $timeoutMs outside [$MIN_TIMEOUT_MS, $MAX_TIMEOUT_MS]"
            }
            require(memoryBytes in MIN_MEMORY_BYTES..MAX_MEMORY_BYTES) {
                "memoryBytes $memoryBytes outside [$MIN_MEMORY_BYTES, $MAX_MEMORY_BYTES]"
            }
            require(maxSourceBytes in 1..MAX_SOURCE_BYTES_CAP) {
                "maxSourceBytes $maxSourceBytes outside [1, $MAX_SOURCE_BYTES_CAP]"
            }
            require(maxInputBytes in 1..MAX_INPUT_BYTES_CAP) {
                "maxInputBytes $maxInputBytes outside [1, $MAX_INPUT_BYTES_CAP]"
            }
            require(maxOutputBytes in 1..MAX_OUTPUT_BYTES_CAP) {
                "maxOutputBytes $maxOutputBytes outside [1, $MAX_OUTPUT_BYTES_CAP]"
            }
        }

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeLong(timeoutMs)
        dest.writeLong(memoryBytes)
        dest.writeInt(maxSourceBytes)
        dest.writeInt(maxInputBytes)
        dest.writeInt(maxOutputBytes)
    }

    companion object {
        // §4.1: Wall time 10 s default; a user may raise a single run to 30 s, never more.
        const val MIN_TIMEOUT_MS: Long = 100L
        const val MAX_TIMEOUT_MS: Long = 30_000L
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L

        // §4.1: QuickJS heap 64 MiB default; user settings may lower it.
        const val MIN_MEMORY_BYTES: Long = 1L * 1024 * 1024
        const val MAX_MEMORY_BYTES: Long = 1L * 1024 * 1024 * 1024
        const val DEFAULT_MEMORY_BYTES: Long = 64L * 1024 * 1024

        // §4.1: Source 256 KiB, cannot be raised by the model (cap is the hard ceiling).
        const val DEFAULT_MAX_SOURCE_BYTES: Int = 256 * 1024
        const val MAX_SOURCE_BYTES_CAP: Int = 1024 * 1024

        // §4.1 / §3.2: Input JSON 2 MiB (10 MiB Workspace files must be chunked by the Agent).
        const val DEFAULT_MAX_INPUT_BYTES: Int = 2 * 1024 * 1024
        const val MAX_INPUT_BYTES_CAP: Int = 32 * 1024 * 1024

        // §4.1: Output 256 KiB.
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 256 * 1024
        const val MAX_OUTPUT_BYTES_CAP: Int = 2 * 1024 * 1024

        val DEFAULTS: JsExecutionLimits = JsExecutionLimits()

        @JvmField
        val CREATOR: Parcelable.Creator<JsExecutionLimits> =
            object : Parcelable.Creator<JsExecutionLimits> {
                override fun createFromParcel(source: Parcel): JsExecutionLimits =
                    JsExecutionLimits(
                        timeoutMs = source.readLong(),
                        memoryBytes = source.readLong(),
                        maxSourceBytes = source.readInt(),
                        maxInputBytes = source.readInt(),
                        maxOutputBytes = source.readInt(),
                    )

                override fun newArray(size: Int): Array<JsExecutionLimits?> = arrayOfNulls(size)

                // Present for minSdk 29 runtimes: the pre-API-30 `Parcelable.Creator`
                // declares `newInstance(int[])`. It is only invoked for parcelled arrays,
                // which this protocol never writes, so the plain method form is a safe
                // compatibility no-op.
                fun newInstance(size: IntArray): Array<JsExecutionLimits?> = arrayOfNulls(size.size)
            }
    }
}
