package com.helix.provider.api.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Real host-process death, deliberately bypassing publish's finally block. */
class ModelPublicationCrashTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun killedPublisherLeavesOnlyRecoverableResidueAndPreservesExistingAsset() {
        val store = ModelAssetStore(directory.root)
        val oldBytes = byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0, 1)
        val oldHash = digest(oldBytes)
        val asset = store.publish(oldHash, oldBytes.size.toLong(), oldBytes.inputStream())
        val classpath =
            listOf(ModelPublicationCrashProbe::class.java, ModelAssetStore::class.java, Unit::class.java)
                .map {
                    File(
                        it.protectionDomain.codeSource.location
                            .toURI(),
                    ).path
                }.distinct()
                .joinToString(File.pathSeparator)
        val output = directory.root.resolve("child.log")
        val child =
            ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").path,
                "-cp",
                classpath,
                ModelPublicationCrashProbe::class.java.name,
                directory.root.path,
            ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue("publisher did not finish: ${output.readText()}", child.waitFor(20, TimeUnit.SECONDS))
            assertEquals(output.readText(), 73, child.exitValue())
            val reopened = ModelAssetStore(directory.root)
            assertEquals(listOf(asset), reopened.list())
            assertEquals(oldBytes.toList(), reopened.verifiedFile(asset).readBytes().toList())
            val residue = reopened.publicationResidue()
            assertEquals(1, residue.count)
            assertTrue(residue.bytes > 0)
            reopened.clearPublicationResidue(residue)
            assertEquals(0, reopened.publicationResidue().count)
            assertEquals(listOf(asset), reopened.list())
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }
}

/** Separate JVM entry point: death occurs after streaming bytes and before fsync/publication. */
object ModelPublicationCrashProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val bytes = byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0, 2)
        val source =
            object : ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (available() == 0) Runtime.getRuntime().halt(73)
                    return super.read(buffer, offset, length)
                }
            }
        ModelAssetStore(File(args.single())).publish(digest(bytes), bytes.size.toLong(), source)
        error("publisher unexpectedly survived")
    }
}

private fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
