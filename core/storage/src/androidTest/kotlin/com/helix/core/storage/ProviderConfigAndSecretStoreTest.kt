package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.ProviderConfigSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * HXA-020 device coverage: the typed [ProviderConfigSpec] pipeline (save/overwrite/delete,
 * canonical endpoint + header storage, validation rejections) and the real
 * [AndroidKeystoreSecretStore] (round-trip, overwrite, idempotent delete, tamper detection,
 * no plaintext on disk).
 */
@RunWith(AndroidJUnit4::class)
class ProviderConfigAndSecretStoreTest {
    private lateinit var context: Context

    @Before
    fun initContext() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun saveStoresCanonicalEndpointAndHeaders() {
        withStorage("provider-canonical.db") { storage ->
            val entity =
                storage.providerConfigs.save(
                    ProviderConfigSpec(
                        id = "provider-canonical",
                        displayName = "Canonical",
                        protocol = ProviderProtocol.OPENAI_RESPONSES,
                        endpoint = "HTTPS://API.Example.com:443/v1",
                        model = "model-1",
                        headersJson = """{"X-Org":"  acme  "}""",
                        secretAlias = "provider-canonical-alias",
                        capabilitySnapshot = "{}",
                    ),
                )
            // Endpoint is stored normalized (lowercase host, canonical port/path).
            assertEquals("https://api.example.com:443/v1", entity.endpoint)
            // Headers are stored in canonical sorted, lowercased, trimmed form.
            assertEquals("""{"x-org":"acme"}""", entity.headersJson)
            assertEquals("OPENAI_RESPONSES", entity.protocol)
            assertEquals(
                "provider-canonical-alias",
                storage.providerConfigs.resolve("provider-canonical").secretAlias,
            )
        }
    }

    @Test
    fun duplicateSaveIsRejected() {
        withStorage("provider-dup.db") { storage ->
            storage.providerConfigs.save(spec("provider-dup"))
            val thrown =
                runCatching {
                    storage.providerConfigs.save(spec("provider-dup", model = "other-model"))
                }.exceptionOrNull()
            assertTrue(
                "duplicate save must throw SQLiteConstraintException, was: $thrown",
                thrown is android.database.sqlite.SQLiteConstraintException,
            )
            assertEquals("model-1", storage.providerConfigs.resolve("provider-dup").model)
        }
    }

    @Test
    fun overwriteReplacesTheRow() {
        withStorage("provider-overwrite.db") { storage ->
            storage.providerConfigs.save(spec("provider-ow"))
            val replaced = storage.providerConfigs.overwrite(spec("provider-ow", model = "model-2"))
            assertEquals("model-2", replaced.model)
            assertEquals("model-2", storage.providerConfigs.resolve("provider-ow").model)
            assertEquals(1, storage.providerConfigs.list().size)
            // Overwrite is also the path for creating a new id.
            storage.providerConfigs.overwrite(spec("provider-ow-2"))
            assertEquals(2, storage.providerConfigs.list().size)
        }
    }

    @Test
    fun overwriteKeepsSessionProviderBindings() {
        // M3 closeout review bug: the old REPLACE upsert was a DELETE+INSERT under the
        // hood, and sessions.providerId is ON DELETE SET NULL — editing a provider
        // silently UNBOUND every session of it (the user changed an endpoint and all
        // their sessions lost their provider, blocking them with "no provider bound").
        // The in-place update must keep every binding intact.
        withStorage("provider-bind.db") { storage ->
            storage.providerConfigs.save(spec("provider-bind"))
            val session =
                storage.sessions.create(
                    id = "session-bind",
                    title = "bound",
                    providerId = "provider-bind",
                    modelId = null,
                    createdAt = 1L,
                )
            assertEquals("provider-bind", session.providerId)
            // Edit the provider (the ProviderService.update path).
            storage.providerConfigs.overwrite(spec("provider-bind", model = "model-2"))
            assertEquals("model-2", storage.providerConfigs.resolve("provider-bind").model)
            // The binding must survive the edit.
            assertEquals(
                "editing a provider must not unbind its sessions",
                "provider-bind",
                storage.sessions.resolve("session-bind").providerId,
            )
            // A second edit (update path again, not just the first overwrite) keeps it too.
            storage.providerConfigs.overwrite(spec("provider-bind", model = "model-3"))
            assertEquals(
                "provider-bind",
                storage.sessions.resolve("session-bind").providerId,
            )
        }
    }

    @Test
    fun deleteRemovesTheRowAndMissingIdFails() {
        withStorage("provider-delete.db") { storage ->
            storage.providerConfigs.save(spec("provider-del"))
            storage.providerConfigs.delete("provider-del")
            assertTrue(storage.providerConfigs.list().isEmpty())
            assertThrows(IllegalArgumentException::class.java) { storage.providerConfigs.resolve("provider-del") }
            assertThrows(IllegalArgumentException::class.java) { storage.providerConfigs.delete("provider-del") }
        }
    }

    @Test
    fun specValidationRejectsUnsafeInput() {
        withStorage("provider-invalid.db") { storage ->
            val invalid =
                listOf(
                    { spec("provider-x", endpoint = "ftp://x.com") }, // non-http(s)
                    { spec("provider-x", endpoint = "http://user:pw@x.com") }, // userinfo
                    { spec("provider-x", endpoint = "http://x.com:0") }, // bad port
                    { spec("provider-x", headersJson = """{"Authorization":"x"}""") }, // credential header
                    { spec("provider-x", headersJson = "not json") }, // corrupted headers
                    { spec("provider-x", secretAlias = "../escape") }, // alias traversal
                    { spec("provider-x", displayName = "   ") }, // blank display name
                    { spec("provider-x", model = "a\u0001b") }, // control char in model
                    { spec("", capabilitySnapshot = "{}") }, // invalid provider id
                )
            invalid.forEach { build ->
                assertThrows(IllegalArgumentException::class.java) { storage.providerConfigs.save(build()) }
            }
            assertTrue(storage.providerConfigs.list().isEmpty())
        }
    }

    @Test
    fun keystoreStoreRoundTripsSecrets() {
        val store = newKeystoreStore("roundtrip")
        val alias = SecretAlias("provider-roundtrip-1")
        assertFalse(store.contains(alias))
        store.put(alias, "sk-live-value-123")
        assertTrue(store.contains(alias))
        assertEquals("sk-live-value-123", store.get(alias))
        assertTrue(store.aliases().contains(alias))
    }

    @Test
    fun keystoreStoreOverwritesAndDeletesIdempotently() {
        val store = newKeystoreStore("overwrite")
        val alias = SecretAlias("provider-overwrite-1")
        store.put(alias, "first")
        store.put(alias, "second")
        assertEquals("second", store.get(alias))
        store.delete(alias)
        assertFalse(store.contains(alias))
        store.delete(alias) // idempotent
        assertFalse(store.aliases().contains(alias))
        assertThrows(IllegalArgumentException::class.java) { store.get(alias) }
    }

    @Test
    fun keystoreStoreRejectsMissingAndUnsafeAliases() {
        val store = newKeystoreStore("missing")
        assertThrows(IllegalArgumentException::class.java) { store.get(SecretAlias("provider-never-stored")) }
        // Unsafe aliases never reach the store: construction fails closed.
        assertThrows(IllegalArgumentException::class.java) { SecretAlias("../evil") }
        assertThrows(IllegalArgumentException::class.java) { SecretAlias("") }
    }

    @Test
    fun keystoreStoreRejectsSizeViolations() {
        val store = newKeystoreStore("size")
        val alias = SecretAlias("provider-size-1")
        assertThrows(IllegalArgumentException::class.java) { store.put(alias, "") }
        assertThrows(IllegalArgumentException::class.java) {
            store.put(alias, "x".repeat(AndroidKeystoreSecretStore.MAX_SECRET_BYTES + 1))
        }
        assertFalse(store.contains(alias))
    }

    @Test
    fun keystoreStoreFilesContainNoPlaintext() {
        val store = newKeystoreStore("no-plaintext")
        val alias = SecretAlias("provider-no-plaintext-1")
        // Distinctive marker without a key-like prefix (the repo's secret gate scans for them).
        val secret = "plain-marker-value-4f7a9c"
        store.put(alias, secret)
        val file = File(storeDirectory("no-plaintext"), alias.value + ".enc")
        assertTrue("secret file must exist", file.isFile)
        val bytes = file.readBytes()
        val plain = secret.toByteArray(Charsets.UTF_8)
        assertFalse(
            "secret file must not contain the plaintext",
            bytes.containsSubsequence(plain),
        )
        // A second put rotates the IV: two ciphertexts for the same secret differ.
        store.put(alias, secret)
        val bytes2 = file.readBytes()
        assertTrue(
            "IV rotation must produce different ciphertext",
            !bytes.contentEquals(bytes2),
        )
    }

    @Test
    fun keystoreStoreFailsClosedOnTamper() {
        val store = newKeystoreStore("tamper")
        val alias = SecretAlias("provider-tamper-1")
        store.put(alias, "sk-tamper-value")
        val file = File(storeDirectory("tamper"), alias.value + ".enc")
        val bytes = file.readBytes()
        // Flip the final byte deterministically (guaranteed change, hits the GCM tag).
        bytes[bytes.size - 1] = if (bytes[bytes.size - 1] == 0.toByte()) 1.toByte() else 0.toByte()
        file.writeBytes(bytes)
        // AEAD tag mismatch must fail closed.
        assertThrows(IllegalArgumentException::class.java) { store.get(alias) }
    }

    private fun spec(
        id: String,
        endpoint: String = "http://127.0.0.1:11434/v1",
        headersJson: String = "{}",
        secretAlias: String = "provider-alias-1",
        displayName: String = "Spec Provider",
        model: String = "model-1",
        capabilitySnapshot: String = "{}",
    ) = ProviderConfigSpec(
        id = id,
        displayName = displayName,
        protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
        endpoint = endpoint,
        model = model,
        headersJson = headersJson,
        secretAlias = secretAlias,
        capabilitySnapshot = capabilitySnapshot,
    )

    private fun newKeystoreStore(name: String): AndroidKeystoreSecretStore =
        AndroidKeystoreSecretStore(File(context.cacheDir, "secrets-$name")).also {
            storeDirectory(name).deleteRecursively()
        }

    private fun storeDirectory(name: String) = File(context.cacheDir, "secrets-$name")

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        var found = false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            found = true
            break
        }
        return found
    }

    private inline fun withStorage(
        dbName: String,
        block: (HelixStorage) -> Unit,
    ) {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        File(context.cacheDir, "content-$dbName").deleteRecursively()
        val db = Room.databaseBuilder(context, HelixDatabase::class.java, dbName).build()
        val storage = HelixStorage(db, FileContentStore(File(context.cacheDir, "content-$dbName")), TestSecretStore())
        try {
            block(storage)
        } finally {
            db.close()
        }
    }
}
