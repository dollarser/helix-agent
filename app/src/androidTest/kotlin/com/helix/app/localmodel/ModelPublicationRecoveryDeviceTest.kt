package com.helix.app.localmodel

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.provider.api.local.ModelAssetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** Dedicated setup/recover runner: kills the actual app during publication, not a synthetic reopen. */
class ModelPublicationRecoveryDeviceTest {
    @Test
    fun interruptedPublicationPreservesOldAssetAndCanBeCleaned() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir.resolve("publication-recovery-fixture")
        val pidFile = context.noBackupFilesDir.resolve("recovery-device-pid")
        val bytes = byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0, 1)
        val setup = InstrumentationRegistry.getArguments().getString("recoveryPhase")?.startsWith("setup") == true
        if (setup) {
            check(!root.exists()) { "Fixture must start in a fresh owned test installation" }
            val store = ModelAssetStore(root)
            store.publish(hash(bytes), bytes.size.toLong(), bytes.inputStream())
            pidFile.writeText(Process.myPid().toString())
            val candidate = bytes + 2
            val source =
                object : ByteArrayInputStream(candidate) {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (available() == 0) {
                            Process.killProcess(Process.myPid())
                            error("Expected process death before publication")
                        }
                        return super.read(buffer, offset, length)
                    }
                }
            store.publish(hash(candidate), candidate.size.toLong(), source)
            error("Expected publication to be interrupted")
        }
        try {
            assertNotEquals(pidFile.readText().toInt(), Process.myPid())
            val store = ModelAssetStore(root)
            val old = store.list().single()
            assertEquals(hash(bytes), old.sha256)
            assertEquals(bytes.toList(), store.verifiedFile(old).readBytes().toList())
            val residue = store.publicationResidue()
            assertEquals(1, residue.count)
            assertTrue(residue.bytes > 0)
            store.clearPublicationResidue(residue)
            assertEquals(0, store.publicationResidue().count)
            val candidate = bytes + 2
            val replacement = store.publish(hash(candidate), candidate.size.toLong(), candidate.inputStream())
            assertEquals(candidate.toList(), store.verifiedFile(replacement).readBytes().toList())
            assertEquals(bytes.toList(), store.verifiedFile(old).readBytes().toList())
        } finally {
            root.deleteRecursively()
            pidFile.delete()
        }
    }

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
