package com.helix.core.storage.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException

class PortableBackupFoundationsTest {
    @Test fun credentialEnvelopeRequiresPasswordAndAuthenticatesCiphertext() {
        val password = "synthetic-passphrase".toCharArray()
        val clear = "{\"synthetic-alias\":\"FAKE_TEST_KEY_NOT_A_REAL_SECRET\"}".toByteArray()
        val context = "a".repeat(64)
        val first = CredentialBackupCipher.encrypt(clear, password, context)
        val second = CredentialBackupCipher.encrypt(clear, password, context)
        assertFalse(first.contentEquals(second))
        assertFalse(first.decodeToString().contains("FAKE_TEST_KEY"))
        assertArrayEquals(clear, CredentialBackupCipher.decrypt(first, password, context))
        assertThrows(AEADBadTagException::class.java) {
            CredentialBackupCipher.decrypt(first, "different-passphrase".toCharArray(), context)
        }
        val envelope =
            kotlinx.serialization.json.Json.parseToJsonElement(
                first.decodeToString(),
            ) as kotlinx.serialization.json.JsonObject
        val ciphertext =
            java.util.Base64.getDecoder().decode(
                (envelope.getValue("ciphertext") as kotlinx.serialization.json.JsonPrimitive).content,
            )
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        val altered =
            kotlinx.serialization.json
                .JsonObject(
                    envelope + (
                        "ciphertext" to
                            kotlinx.serialization.json.JsonPrimitive(
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString(ciphertext),
                            )
                    ),
                ).toString()
                .toByteArray()
        assertThrows(AEADBadTagException::class.java) { CredentialBackupCipher.decrypt(altered, password, context) }
        val damaged = first.decodeToString().replace("600000", "1").toByteArray()
        assertThrows(
            IllegalArgumentException::class.java,
        ) { CredentialBackupCipher.decrypt(damaged, password, context) }
        val otherContext = "b".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackupCipher.decrypt(first, password, otherContext)
        }
        val rewritten =
            kotlinx.serialization.json
                .JsonObject(
                    envelope + (
                        "contextSha256" to
                            kotlinx.serialization.json.JsonPrimitive(otherContext)
                    ),
                ).toString()
                .toByteArray()
        assertThrows(AEADBadTagException::class.java) {
            CredentialBackupCipher.decrypt(rewritten, password, otherContext)
        }
        password.fill('\u0000')
    }

    private fun repackDamaged(
        bytes: ByteArray,
        damaged: ByteArrayOutputStream,
    ) {
        ZipOutputStream(damaged).use { output ->
            ZipInputStream(bytes.inputStream()).use { input ->
                copyDamagedEntries(input, output)
            }
        }
    }

    private fun copyDamagedEntries(
        input: ZipInputStream,
        output: ZipOutputStream,
    ) {
        while (true) {
            val entry = input.nextEntry ?: break
            val payload = input.readBytes()
            if (entry.name == "state.json") payload[0] = '['.code.toByte()
            output.putNextEntry(ZipEntry(entry.name))
            output.write(payload)
            output.closeEntry()
        }
    }

    @Test fun streamingArchiveRejectsTraversalAndHashDamageBeforePublication() {
        val root = Files.createTempDirectory("portable-archive").toFile()
        try {
            val state = File(root, "state").apply { writeText("{\"format\":1}") }
            val bytes = ByteArrayOutputStream()
            val codec = AppBackupArchive(AppBackupLimits(1024, 512, 512, 4096, 4))
            codec.write(mapOf("state.json" to state), bytes) {}
            val files = codec.read(bytes.toByteArray().inputStream(), File(root, "read")) {}
            assertEquals(state.readText(), files.getValue("state.json").readText())
            // Repack with valid ZIP CRCs but altered payload: only the manifest digest detects this.
            val damaged = ByteArrayOutputStream()
            repackDamaged(bytes.toByteArray(), damaged)
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    codec.read(damaged.toByteArray().inputStream(), File(root, "damaged")) {}
                }
            assertEquals("BACKUP_HASH_INVALID", failure.message)
            val malformed = ByteArrayOutputStream()
            ZipOutputStream(malformed).use {
                it.putNextEntry(ZipEntry("../private"))
                it.write(1)
                it.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) {
                codec.read(malformed.toByteArray().inputStream(), File(root, "bad")) {}
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun processDeathAtEveryMoveRollsBackAllRootsBeforeOpeningConsumers() {
        for (step in 0..3) {
            val root = Files.createTempDirectory("portable-journal").toFile()
            try {
                val base = File(root, "app").apply { mkdirs() }
                val stage = File(root, "stage").apply { mkdirs() }
                val original = File(base, "state").apply { writeText("old") }
                val first = File(stage, "first").apply { writeText("new") }
                val second = File(stage, "second").apply { writeText("attachment") }
                var calls = 0
                val interrupted =
                    RestoreFileTransaction(base, stage, { it in setOf("state", "attachment") }) { _, _ ->
                        if (calls++ == step) throw SyntheticProcessDeath()
                    }
                interrupted.queue(linkedMapOf("state" to first, "attachment" to second))
                assertThrows(SyntheticProcessDeath::class.java) { interrupted.recover() }
                val recovered = RestoreFileTransaction(base, stage, { it in setOf("state", "attachment") })
                assertEquals(RestoreFileTransaction.Outcome.ROLLED_BACK, recovered.recover())
                assertEquals("old", original.readText())
                assertFalse(File(base, "attachment").exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test fun ioFailureIsNotSuccessAndCommittedRestoreCanBeRolledBack() {
        val root = Files.createTempDirectory("portable-commit").toFile()
        try {
            val base = File(root, "app").apply { mkdirs() }
            val stage = File(root, "stage").apply { mkdirs() }
            File(base, "data").writeText("old")
            val incoming = File(stage, "incoming").apply { writeText("new") }
            val failed = RestoreFileTransaction(base, stage, { it == "data" }) { _, _ -> throw IOException("fixture") }
            failed.queue(mapOf("data" to incoming))
            val rollback = assertThrows(RestoreRolledBackException::class.java) { failed.recover() }
            assertEquals("fixture", rollback.cause?.message)
            assertEquals("old", File(base, "data").readText())
            val next = File(root, "next").apply { mkdirs() }
            val replacement = File(next, "incoming").apply { writeText("new") }
            val transaction = RestoreFileTransaction(base, next, { it == "data" })
            transaction.queue(mapOf("data" to replacement))
            assertEquals(RestoreFileTransaction.Outcome.RESTORED, transaction.recover())
            assertEquals("new", File(base, "data").readText())
            transaction.requestRollback()
            assertEquals(RestoreFileTransaction.Outcome.ROLLED_BACK, transaction.recover())
            assertEquals("old", File(base, "data").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private class SyntheticProcessDeath : Error()
}
