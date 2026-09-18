package com.helix.app.proot

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.SubscriptionProviderModule
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ProviderProtocol
import com.helix.core.storage.repository.ProviderConfigSpec
import com.helix.runtime.cli.client.CliModelJobClient
import com.helix.runtime.cli.client.CliModelJobRecord
import com.helix.runtime.cli.client.CliModelJobRecordCodec
import com.helix.runtime.cli.client.CliModelJobState
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import com.helix.runtime.proot.client.VerifiedRuntimeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/** Same test source compiled independently against old and new production commits.
 * Seed runs only under the old APK; verify runs after a real install -r, never clearing data.
 */
class ApkReplacementUpgradeDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val storage get() = app.appContainer.storage
    private val client = CliModelJobClient(CliRuntimeSupervisor(app))
    private val marker get() = File(app.noBackupFilesDir, "apk-upgrade-evidence")

    @Test
    fun dataAndRuntimeEvidenceSurviveApkReplacement() {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        // This two-APK journey requires the dedicated runner; ordinary suites cannot seed it.
        assumeNotNull(phase)
        when (phase) {
            "seed" -> seedWithOldCode()
            "verify" -> verifyWithNewCode()
            else -> error("Run only with the owned APK-replacement runner")
        }
    }

    private fun seedWithOldCode() {
        assertTrue("fresh owned installation required", !marker.exists())
        marker.mkdirs()
        val original = storage.providerConfigs.resolve(SubscriptionProviderModule.CODEX_ID)
        storage.providerConfigs.overwrite(
            ProviderConfigSpec(
                original.id,
                "Upgrade preserved provider",
                ProviderProtocol.parse(original.protocol),
                original.endpoint,
                "helix-fixture",
                original.headersJson,
                original.secretAlias,
                original.capabilitySnapshot,
            ),
        )
        storage.sessions.create(SESSION, "Upgrade preserved session", original.id, "helix-fixture", 1234L)
        storage.messages.append("upgrade-user", SESSION, null, "USER", "TEXT", "Persist this user message")
        val completed =
            client.submitAndAwait(
                SUCCEEDED,
                ModelRequest("helix-fixture", listOf(ModelMessage(ModelRole.USER, "fixture"))),
                timeoutMs = 30000,
            ) as CliModelJobClient.AwaitOutcome.Terminal
        assertEquals(CliModelJobState.SUCCEEDED, completed.record.state)
        assertTrue(completed.events!!.any { it is ModelEvent.TextDelta && it.text == "HELIX_OK" })
        assertNull(completed.record.reconciledAtEpochMillis)
        File(marker, "finished-record.json").writeText(CliModelJobRecordCodec.encode(completed.record))
        storage.messages.append("upgrade-result", SESSION, null, "ASSISTANT", "TEXT", "HELIX_OK")
        File(app.filesDir, "upgrade-user-artifact.txt").writeText("preserve exact artifact bytes")

        // Old-code serialized interruption fixture: do not claim this partial request executed.
        client.debugKillRuntime()
        val pendingDir = File(app.filesDir, "provider-v1/codex-model-jobs/$PENDING").apply { mkdirs() }
        File(pendingDir, "record.json").writeText(
            CliModelJobRecordCodec.encode(
                CliModelJobRecord(PENDING, "a".repeat(64), CliModelJobState.PENDING, 1234L),
            ),
        )
        File(pendingDir, "request.json").writeText("incomplete old-version fixture")
        VerifiedRuntimeStore(app).clear()
        val descriptor =
            com.helix.runtime.proot.ipc.RuntimeTargetDescriptorCodec.parse(
                com.helix.runtime.proot.app.ProotHandshakeManifest
                    .build(app)
                    .decodeToString(),
            )
        val legacy = File(app.filesDir, "proot-runtime/verified-runtime.json")
        VerifiedRuntimeStore(legacy).save(VerifiedRuntimeStore.Entry(descriptor, 1234L))
        File(marker, "legacy-anchor.json").writeText(legacy.readText())
        File(marker, "provider.txt").writeText(storage.providerConfigs.resolve(original.id).toString())
        File(marker, "pid").writeText(Process.myPid().toString())
        File(marker, "uid").writeText(Process.myUid().toString())
    }

    private fun verifyWithNewCode() {
        assertTrue("must preserve old APK data", marker.isDirectory)
        assertNotEquals(File(marker, "pid").readText().toInt(), Process.myPid())
        assertEquals(File(marker, "uid").readText().toInt(), Process.myUid())
        val session = storage.sessions.resolve(SESSION)
        assertEquals("Upgrade preserved session", session.title)
        assertEquals(SubscriptionProviderModule.CODEX_ID, session.providerId)
        assertEquals("helix-fixture", session.modelId)
        assertEquals(
            listOf("Persist this user message", "HELIX_OK"),
            storage.messages.listBySession(SESSION).map { storage.messages.readContent(it) },
        )
        assertEquals(
            File(marker, "provider.txt").readText(),
            storage.providerConfigs.resolve(SubscriptionProviderModule.CODEX_ID).toString(),
        )
        assertEquals("preserve exact artifact bytes", File(app.filesDir, "upgrade-user-artifact.txt").readText())
        assertNull("legacy anchor must not activate integrated runtime", VerifiedRuntimeStore(app).load())
        assertEquals(
            File(marker, "legacy-anchor.json").readText(),
            File(app.filesDir, "proot-runtime/verified-runtime.json").readText(),
        )

        val before = CliModelJobRecordCodec.decode(File(marker, "finished-record.json").readText())
        val fetched = client.fetchResult(SUCCEEDED) as CliModelJobClient.StateOutcome.Ok
        assertEquals(before, fetched.record)
        assertTrue(fetched.events!!.any { it is ModelEvent.TextDelta && it.text == "HELIX_OK" })
        val interrupted = (client.query(PENDING) as CliModelJobClient.StateOutcome.Ok).record
        assertEquals(CliModelJobState.INTERRUPTED, interrupted.state)
        assertEquals("a".repeat(64), interrupted.requestSha256)
        assertNotNull(interrupted.terminalAtEpochMillis)
        assertNull((client.reconcile(PENDING) as CliModelJobClient.StateOutcome.Ok).events)
        client.acknowledgeResult(fetched.record)
        assertNotNull((client.query(SUCCEEDED) as CliModelJobClient.StateOutcome.Ok).record.reconciledAtEpochMillis)
        assertNull((client.reconcile(SUCCEEDED) as CliModelJobClient.StateOutcome.Ok).events)
    }

    private companion object {
        const val SESSION = "apk-upgrade-session"
        const val SUCCEEDED = "job_000000001931"
        const val PENDING = "job_000000001932"
    }
}
