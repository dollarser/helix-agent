package com.helix.runtime.proot.core

/** HXA-240: command/bind preparation only. The existing PRoot process owns every execution. */
object GuestMedia {
    const val GUEST_ROOT = "/opt/helix-media"
    const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val LINKER_CONFIG = "/linkerconfig/ld.config.txt"

    // The guest /opt executable does not match Android's /system or /data namespace routes.
    // ICU moved from the runtime APEX (Android 10) to the i18n APEX on newer releases.
    const val LIBRARY_PATH = "$GUEST_ROOT/lib:/apex/com.android.i18n/lib64:/apex/com.android.runtime/lib64"
    val programs: Set<String> = setOf("ffmpeg", "ffprobe")
    val libraries: Set<String> =
        setOf("libavcodec.so", "libavformat.so", "libavfilter.so", "libavutil.so", "libswscale.so", "libswresample.so")
    val systemPaths: Set<String> =
        setOf("/system", "/apex", "/vendor", "/product", "/system_ext", "/odm", LINKER_CONFIG)

    /** Quoted argv is forwarded byte-for-byte by the shell; no eval or reconstructed command line. */
    fun script(program: String): String {
        require(program in programs)
        return "#!/bin/sh\n" +
            "unset LD_PRELOAD LD_AUDIT\n" +
            "export LD_LIBRARY_PATH=$LIBRARY_PATH\n" +
            "exec /system/bin/linker64 $GUEST_ROOT/lib/libhelix_$program.so \"\$@\"\n"
    }

    fun bindings(
        nativeDirectory: String,
        commandDirectory: String,
        availableSystemPaths: Set<String>,
    ): List<String> {
        require(availableSystemPaths.all { it in systemPaths })
        require("/system" in availableSystemPaths && "/apex" in availableSystemPaths)
        listOf(nativeDirectory, commandDirectory).forEach { path ->
            require(path.startsWith('/') && ':' !in path && path.none(Char::isISOControl))
        }
        return availableSystemPaths.sorted().flatMap { listOf("-b", it) } +
            listOf("-b", "$nativeDirectory:$GUEST_ROOT/lib", "-b", "$commandDirectory:$GUEST_ROOT/bin")
    }

    fun path(existing: String?): String = "$GUEST_ROOT/bin:${existing ?: DEFAULT_PATH}"
}
