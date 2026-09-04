package com.helix.runtime.proot.client

import com.helix.runtime.proot.ipc.RuntimeTargetDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VerifiedRuntimeStoreTest {
    @Rule
    @JvmField
    var tmp = TemporaryFolder()

    private val fingerprint = "a1b2c3d4e5f6".repeat(5) + "a1b2"
    private lateinit var file: File
    private lateinit var store: VerifiedRuntimeStore

    @Before
    fun setUp() {
        val dir = tmp.newFolder("store")
        file = File(dir, "verified-runtime.json")
        store = VerifiedRuntimeStore(file)
    }

    private fun entry(
        lockSha256: String = fingerprint,
        whenMs: Long = 1_700_000_000_000L,
    ) = VerifiedRuntimeStore.Entry(
        descriptor = RuntimeTargetDescriptor(1, "0.1.0", "arm64-v8a", lockSha256, listOf("handshake")),
        verifiedAtEpochMs = whenMs,
    )

    @Test
    fun `missing file loads as no anchor`() {
        assertNull(store.load())
    }

    @Test
    fun `save and load round-trip the entry`() {
        store.save(entry())
        assertEquals(entry(), store.load())
    }

    @Test
    fun `save overwrites a previous anchor`() {
        store.save(entry(whenMs = 1L))
        store.save(entry(whenMs = 2L))
        assertEquals(2L, store.load()?.verifiedAtEpochMs)
    }

    @Test
    fun `corrupt json loads as no anchor`() {
        store.save(entry())
        file.writeText("{this is not json")
        assertNull(store.load())
    }

    @Test
    fun `unexpected document keys load as no anchor`() {
        store.save(entry())
        file.writeText("""{"descriptor":{},"verifiedAtEpochMs":1,"extra":2}""")
        assertNull(store.load())
    }

    @Test
    fun `document without the verified timestamp loads as no anchor`() {
        store.save(entry())
        file.writeText(
            """{"descriptor":{"protocolVersion":1,"runtimeVersion":"0.1.0","abi":"arm64-v8a","""" +
                """"lockSha256":"$fingerprint","capabilities":["handshake"]}}""",
        )
        assertNull(store.load())
    }

    @Test
    fun `invalid descriptor inside the document loads as no anchor`() {
        store.save(entry())
        file.writeText(file.readText().replace("\"abi\":\"arm64-v8a\"", "\"abi\":\"riscv64\""))
        assertNull(store.load())
    }

    @Test
    fun `clear removes the anchor`() {
        store.save(entry())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `clear on a missing anchor is a no-op`() {
        store.clear()
        assertNull(store.load())
    }
}
