package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestMediaTest {
    @Test
    fun `commands forward all arguments without eval or another engine`() {
        GuestMedia.programs.forEach { program ->
            val script = GuestMedia.script(program)
            assertTrue(script.contains("libhelix_$program.so \"\$@\""))
            assertTrue(script.contains("exec /system/bin/linker64"))
            assertFalse(script.contains("eval "))
            assertFalse(script.contains("-filter_complex"))
            assertFalse(script.contains("-c:v"))
        }
    }

    @Test
    fun `real shell forwarding preserves filter graphs unicode empty args and metacharacters`() {
        val root =
            java.nio.file.Files
                .createTempDirectory("ffmpeg-argv-")
                .toFile()
        try {
            val sink = java.io.File(root, "argv")
            val stub = java.io.File(root, "linker")
            stub.writeText("#!/bin/sh\nprintf '%s\\0' \"\$@\" > \"\$SINK\"\nexit 37\n")
            val script = java.io.File(root, "ffmpeg")
            script.writeText(GuestMedia.script("ffmpeg").replace("/system/bin/linker64", "/bin/sh '${stub.path}'"))
            val arguments =
                listOf(
                    "-filter_complex",
                    "[0:v]scale=640:-2[v];[1:a]volume=0.5[a]",
                    "中文 文件.mp4",
                    "",
                    "\$(touch forbidden)",
                    "a'b\"c",
                )
            val process =
                ProcessBuilder(listOf("/bin/sh", script.path) + arguments)
                    .directory(root)
                    .apply { environment()["SINK"] = sink.path }
                    .start()
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(37, process.exitValue())
            assertEquals(
                listOf("/opt/helix-media/lib/libhelix_ffmpeg.so") + arguments,
                sink.readText().split('\u0000').dropLast(1),
            )
            assertFalse(java.io.File(root, "forbidden").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `program name cannot become shell syntax`() {
        GuestMedia.script("ffmpeg; touch /tmp/x")
    }

    @Test
    fun `system binds and guest library identity are explicit`() {
        val binds = GuestMedia.bindings("/data/app/native libs", "/data/user/0/tmp/scripts", setOf("/system", "/apex"))
        assertEquals(
            listOf(
                "-b",
                "/apex",
                "-b",
                "/system",
                "-b",
                "/data/app/native libs:/opt/helix-media/lib",
                "-b",
                "/data/user/0/tmp/scripts:/opt/helix-media/bin",
            ),
            binds,
        )
        assertFalse(binds.any { it == "/data" || it == "/storage" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing bionic environment cannot masquerade as musl`() {
        GuestMedia.bindings("/native", "/scripts", setOf("/system"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `arbitrary private system binds are rejected`() {
        GuestMedia.bindings("/native", "/scripts", setOf("/system", "/apex", "/data"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bind delimiter in a host path is rejected`() {
        GuestMedia.bindings("/native:/", "/scripts", setOf("/system", "/apex"))
    }

    @Test
    fun `path preserves existing programs and provides bridge first`() {
        assertEquals("/opt/helix-media/bin:/custom:/bin", GuestMedia.path("/custom:/bin"))
        assertTrue(GuestMedia.path(null).endsWith(GuestMedia.DEFAULT_PATH))
    }
}
