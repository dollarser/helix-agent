package com.helix.runtime.proot.core

import java.io.File

/** Same bounded fd-relative implementation on Android and the host's native fixture library. */
internal object PtyStateNative {
    init {
        val fixture = System.getProperty("helix.pty.state.library")
        if (fixture == null) System.loadLibrary("proot_native") else System.load(fixture)
    }

    fun tree(
        root: File,
        relative: String,
    ) {
        val code = removeNative(root.path, relative, null, "unused")
        check(code == 0) { "Terminal temporary cleanup failed ($code)" }
    }

    fun metadata(
        root: File,
        name: String,
        expected: ByteArray,
    ): Boolean {
        val code = removeNative(root.path, name, expected, "$name.reclaim")
        check(code >= 0) { "Terminal metadata cleanup failed ($code)" }
        return code == 0
    }

    private external fun removeNative(
        root: String,
        relative: String,
        expected: ByteArray?,
        isolated: String,
    ): Int
}
