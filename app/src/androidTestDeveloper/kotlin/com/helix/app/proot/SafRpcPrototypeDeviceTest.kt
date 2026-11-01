package com.helix.app.proot

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.files.BoundDocumentBackend
import com.helix.app.files.ManualFileOperations
import com.helix.app.files.SafManualFileBackend
import com.helix.core.workspace.FileScopePath
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeGrantCheck
import com.helix.feature.files.SafTreeGrantFacts
import com.helix.feature.files.SafTreeScopeService
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real guest Python→local socket→ContentResolver→synthetic Provider. No production endpoint or URI-to-path guess. */
class SafRpcPrototypeDeviceTest {
    // One owned source/guest/external-edit fixture; socket, input and output close independently.
    @Suppress("NestedBlockDepth", "LongMethod")
    @Test
    fun guestReadsOriginalDocumentsAndRejectsPosixWritesEscapesAndStaleVersion() {
        val f = InPlaceLogCloseoutDeviceTest.Fixture()
        require(f.context.packageName.endsWith(".asrvalidation"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val authority = "${instrumentation.context.packageName}.manualfiles"
        val uri = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val registry = File(f.context.cacheDir, "bridge-grants-${UUID.randomUUID()}.json")
        val grant = SafGrantStore(registry.toPath())
        val id = grant.grant(uri.toString(), "Synthetic bridge").scopeId
        val live = AtomicBoolean(true)
        val service =
            SafTreeScopeService(
                grant,
                SafTreeGrantCheck {
                    SafTreeGrantFacts(live.get(), authority, "root", true, true)
                },
            )
        val backend = SafManualFileBackend(f.context.contentResolver, grant, service, id)
        val manual = ManualFileOperations({ backend }, { live.get() })
        val folder = "bridge-${UUID.randomUUID()}"
        manual.mkdir(id, "", folder)
        backend.create("$folder/source.txt", false)
        backend.write("$folder/source.txt").use { it.write("before edit".toByteArray()) }
        val identity = backend.directoryIdentity(folder)
        val selected =
            BoundDocumentBackend("ws-fixture", { ref ->
                FileScopePath(id, listOf(folder, ref.relativePath).filter(String::isNotEmpty).joinToString("/"))
            }, { backend })
        try {
            SafRpcPrototype(selected) { check(backend.directoryIdentity(folder) == identity) }.use { bridge ->
                instrumentation.context.assets.open("workspace/saf_bridge_client.py").use { input ->
                    File(f.workspace, "saf_bridge_client.py").outputStream().use { input.copyTo(it) }
                }
                File(f.workspace, "check_bridge.py").writeText(checkScript(bridge))
                f.start("python3 check_bridge.py")
                val ready = File(f.workspace, "rpc-read-ready")
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!ready.exists() && System.nanoTime() < deadline) Thread.sleep(25)
                assertTrue("Guest must read the original version before external edit", ready.exists())
                backend.write("$folder/source.txt").use { it.write("after external edit".toByteArray()) }
                File(f.workspace, "rpc-edit-complete").writeText("ready")
                assertEquals(ProotJobState.SUCCEEDED, f.terminal().state)
                assertEquals(
                    "after external edit",
                    backend.read("$folder/source.txt").bufferedReader().use { it.readText() },
                )
                live.set(false)
                assertTrue(request(bridge).contains("unavailable_or_changed"))
                live.set(true)
            }
            assertEquals(listOf("source.txt"), backend.children(folder))
        } finally {
            live.set(true)
            manual.delete(id, folder)
            registry.delete()
            f.close(discardUnstoredResult = true)
        }
    }

    @Test fun closingPrototypeInterruptsIncompleteRequestAndReleasesSocket() {
        val backend =
            object : com.helix.core.workspace.WorkspaceFileBackend {
                override fun validateMutation(path: String) = error("No writes")

                override fun stat(path: String) = error("No document request")

                override fun children(path: String) = error("No document request")

                override fun read(path: String) = error("No document request")

                override fun create(
                    path: String,
                    directory: Boolean,
                ) = error("No writes")

                override fun write(path: String) = error("No writes")

                override fun rename(
                    path: String,
                    destination: String,
                ) = error("No writes")

                override fun delete(path: String) = error("No writes")
            }
        val bridge = SafRpcPrototype(backend) {}
        LocalSocket().use { client ->
            client.connect(LocalSocketAddress(bridge.name))
            client.soTimeout = 5000
            client.outputStream.write('{'.code)
            client.outputStream.flush()
            Thread.sleep(50)
            bridge.close()
            assertEquals(-1, client.inputStream.read())
            bridge.close()
        }
    }

    private fun request(bridge: SafRpcPrototype): String =
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(bridge.name))
            socket.soTimeout = 5000
            val json = """{"nonce":"${bridge.nonce}","operation":"read","path":"source.txt"}"""
            socket.outputStream.write((json + "\n").toByteArray())
            socket.inputStream.bufferedReader().readLine()
        }

    private fun checkScript(bridge: SafRpcPrototype) =
        """
        import subprocess, sys, json, base64, hashlib, time
        from pathlib import Path
        def request(op, path='source.txt', expected=None, nonce='${bridge.nonce}'):
            argv=[sys.executable,'saf_bridge_client.py','${bridge.name}',nonce,op,path]
            if expected: argv.append(expected)
            run=subprocess.run(argv, stdout=subprocess.PIPE, check=False)
            return json.loads(run.stdout), run.returncode
        assert request('ls','')[0]['entries'] == ['source.txt']
        assert request('stat','')[0]['directory'] is True
        data, code=request('read')
        assert code == 0 and data['posix'] is False
        assert base64.b64decode(data['base64']) == b'before edit'
        Path('rpc-read-ready').touch()
        deadline=time.monotonic()+15
        while not Path('rpc-edit-complete').exists():
            assert time.monotonic() < deadline
            time.sleep(0.01)
        assert request('read', expected=data['hash'])[0]['status'] == 'unavailable_or_changed'
        assert base64.b64decode(request('read')[0]['base64']) == b'after external edit'
        for op in ['write','exclusive-create','rename','lock','git-status','git-add','git-commit','git-checkout']:
            assert request(op)[0]['status'] == 'unsupported_posix_or_write'
        assert request('stat','../outside')[1] != 0
        assert request('read', expected=hashlib.sha256(b'before edit').hexdigest())[0]['status'] == 'unavailable_or_changed'
        assert request('read', nonce='forged')[1] != 0
        print('RPC_READ_ORIGINAL_NO_POSIX_MOUNT')
        """.trimIndent()
}
