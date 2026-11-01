package com.helix.app.proot

import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Existing PRoot/Binder; Git writes only a newly owned synthetic repository, never the Helix checkout. */
class DirectWorkspaceDeviceTest {
    @Test fun externalOriginalSupportsGitLocksAndPythonBuildWithoutMirror() {
        val fixture = InPlaceLogCloseoutDeviceTest.Fixture(externalWorkspace = true)
        require(fixture.context.packageName.endsWith(".asrvalidation"))
        try {
            val record = fixture.run(script)
            assertEquals(ProotJobState.SUCCEEDED, record.state)
            assertEquals("direct-original", File(fixture.workspace, "document.txt").readText())
            assertTrue(File(fixture.workspace, ".git/HEAD").isFile)
            assertTrue(File(fixture.workspace, "source.py").readText().contains("synthetic build"))
            assertTrue(File(fixture.workspace, "__pycache__").listFiles().orEmpty().isNotEmpty())
            assertFalse(File(fixture.workspace, "output").exists())
            val durableIdentity =
                try {
                    fixture.storage.workspaces.directoryWitness(fixture.workspace.toPath())
                    true
                } catch (_: java.io.IOException) {
                    false
                }
            val facts =
                kotlinx.serialization.json.buildJsonObject {
                    put("directOriginal", kotlinx.serialization.json.JsonPrimitive(true))
                    put("gitStatusAddCommitCheckout", kotlinx.serialization.json.JsonPrimitive(true))
                    put("pythonCompilation", kotlinx.serialization.json.JsonPrimitive(true))
                    put("exclusiveCreateRenameLock", kotlinx.serialization.json.JsonPrimitive(true))
                    put("durableDirectoryIdentity", kotlinx.serialization.json.JsonPrimitive(durableIdentity))
                    put("newSystemPermission", kotlinx.serialization.json.JsonPrimitive(false))
                }
            File(fixture.context.cacheDir, "workspace-direct-probe.json").writeText(facts.toString())
        } finally {
            fixture.close(discardUnstoredResult = true)
        }
    }

    @Test fun cancellingExternalExecutionKeepsCompletedOriginalWriteWithoutReplay() {
        val fixture = InPlaceLogCloseoutDeviceTest.Fixture(externalWorkspace = true)
        require(fixture.context.packageName.endsWith(".asrvalidation"))
        try {
            fixture.start("printf original > document.txt; sleep 30; printf replay > forbidden.txt")
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!File(fixture.workspace, "document.txt").exists() && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(File(fixture.workspace, "document.txt").exists())
            fixture.client.cancel(fixture.spec.jobId)
            assertEquals(ProotJobState.CANCELLED, fixture.terminal().state)
            assertEquals("original", File(fixture.workspace, "document.txt").readText())
            assertFalse(File(fixture.workspace, "forbidden.txt").exists())
            assertEquals(1, fixture.submissions)
        } finally {
            fixture.close(discardUnstoredResult = true)
        }
    }

    @Test fun abruptChildExitDoesNotUndoOriginalWriteOrRunFollowingCommands() {
        val fixture = InPlaceLogCloseoutDeviceTest.Fixture(externalWorkspace = true)
        require(fixture.context.packageName.endsWith(".asrvalidation"))
        try {
            val result =
                fixture.run(
                    "set -e; printf retained > document.txt; " +
                        "python3 -c 'import os; os._exit(23)'; printf forbidden > forbidden.txt",
                )
            assertEquals(ProotJobState.FAILED, result.state)
            assertEquals("retained", File(fixture.workspace, "document.txt").readText())
            assertFalse(File(fixture.workspace, "forbidden.txt").exists())
            assertEquals(1, fixture.submissions)
        } finally {
            fixture.close(discardUnstoredResult = true)
        }
    }

    private val script =
        """
        set -eu
        export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
        git -c init.defaultBranch=main init -q
        git config core.filemode false
        git config core.hooksPath /dev/null
        printf 'print("synthetic build")\n' > source.py
        git add source.py
        git -c user.name=HelixFixture -c user.email=fixture@example.invalid -c commit.gpgsign=false commit -qm first
        printf 'changed\n' > source.py
        test -n "${'$'}(git status --porcelain)"
        git checkout -- source.py
        python3 -m py_compile source.py
        python3 - <<'PY'
        import os, fcntl
        a = os.open('exclusive.lock', os.O_CREAT|os.O_EXCL|os.O_WRONLY, 0o600)
        try:
            try:
                os.open('exclusive.lock', os.O_CREAT|os.O_EXCL|os.O_WRONLY, 0o600)
                raise AssertionError('exclusive-create failed')
            except FileExistsError:
                pass
            with open('flock.lock', 'w') as x, open('flock.lock', 'w') as y:
                fcntl.flock(x, fcntl.LOCK_EX|fcntl.LOCK_NB)
                try:
                    fcntl.flock(y, fcntl.LOCK_EX|fcntl.LOCK_NB)
                    raise AssertionError('lock exclusion failed')
                except BlockingIOError:
                    pass
            open('rename.a','w').write('new')
            open('rename.b','w').write('old')
            os.replace('rename.a','rename.b')
            assert open('rename.b').read() == 'new' and not os.path.exists('rename.a')
        finally:
            os.close(a)
            os.unlink('exclusive.lock')
        PY
        printf direct-original > document.txt
        """.trimIndent()
}
