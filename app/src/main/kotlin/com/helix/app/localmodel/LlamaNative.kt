package com.helix.app.localmodel

/** Only LocalModelRuntimeService may initialize this object. Never load JNI in the main process. */
internal object LlamaNative {
    init {
        System.loadLibrary("helix_model")
    }

    external fun load(
        fd: Int,
        contextTokens: Int,
        threads: Int,
        memoryBudgetBytes: Long,
    ): Int

    external fun generate(request: ByteArray): ByteArray

    external fun prepare()

    external fun cancel()

    external fun unload()
}
