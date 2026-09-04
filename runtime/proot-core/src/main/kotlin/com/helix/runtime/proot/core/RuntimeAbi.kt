package com.helix.runtime.proot.core

/**
 * CPU ABIs the PRoot Runtime ships for (architecture doc local-code-execution section 6.3:
 * 第一阶段 `arm64-v8a`，CI/模拟器再支持 `x86_64`).
 *
 * The wire form is the exact string recorded in `runtime-lock.json` / `manifest.json`.
 * Parsing is closed: any other string is a schema violation (unknown ABIs fail closed
 * instead of being guessed).
 */
enum class RuntimeAbi(
    val wire: String,
) {
    ARM64_V8A("arm64-v8a"),
    X86_64("x86_64"),
    ;

    companion object {
        /** Closed-set parse; throws [RuntimeLockSchemaException] on any unknown wire form. */
        fun fromWire(wire: String): RuntimeAbi =
            entries.firstOrNull { it.wire == wire }
                ?: throw RuntimeLockSchemaException("unknown runtime ABI: $wire")
    }
}
