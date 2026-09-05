package com.helix.tools.root

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.topjohnwu.superuser.Shell
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.helix.core.model.SystemClock as HelixSystemClock

/** HXA-095 rooted physical-device matrix. It is skipped unless the harness explicitly opts in. */
@RunWith(AndroidJUnit4::class)
class RootHighLevelToolsDeviceTest {
    private val clock = HelixSystemClock()
    private val access = LibsuRootAccess(ApplicationProvider.getApplicationContext())
    private val sessions = RootSessionManager(clock, access::status, access::disconnect)
    private val tools = RootTools(access, sessions)

    @After
    fun tearDown() {
        sessions.close()
    }

    @Test
    fun boundedReadsHonorScopeAndStopAfterRootServiceCrash() {
        assumeTrue(
            "requires the dedicated HXA-095 rooted physical-device harness",
            InstrumentationRegistry.getArguments().getString(EXPECTED_ROOT_ARGUMENT) == "granted",
        )
        assertEquals(RootRequestStatus.STARTED, access.requestRoot())
        awaitAccess { it == RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED) }
        sessions.start(mapOf(SYSTEM_ETC_SCOPE to "/system/etc"))

        val status = completed(RootTools.STATUS)
        assertEquals("granted", status.getValue("grant").jsonPrimitive.content)
        assertEquals("active", status.getValue("session").jsonPrimitive.content)

        val file =
            completed(
                RootTools.FILE_READ,
                "scopeId" to JsonPrimitive(SYSTEM_ETC_SCOPE),
                "relativePath" to JsonPrimitive("hosts"),
                "maxBytes" to JsonPrimitive(1024),
            )
        assertEquals("hosts", file.getValue("relativePath").jsonPrimitive.content)
        assertTrue(
            file
                .getValue("windowLength")
                .jsonPrimitive.content
                .toInt() > 0,
        )

        val targetPackage = ApplicationProvider.getApplicationContext<android.content.Context>().packageName
        val pkg = completed(RootTools.PACKAGE_INFO, "packageName" to JsonPrimitive(targetPackage))
        assertEquals(targetPackage, pkg.getValue("packageName").jsonPrimitive.content)

        val processes = completed(RootTools.PROCESS_LIST, "limit" to JsonPrimitive(20))
        assertTrue(processes.getValue("processes").jsonArray.isNotEmpty())

        val logs =
            completed(
                RootTools.LOG_READ,
                "maxLines" to JsonPrimitive(20),
                "minPriority" to JsonPrimitive("I"),
            )
        assertTrue(logs.getValue("lines").jsonArray.size <= 20)

        val escaped =
            execute(
                RootTools.FILE_READ,
                "scopeId" to JsonPrimitive(SYSTEM_ETC_SCOPE),
                "relativePath" to JsonPrimitive("../../data/system/locksettings.db"),
            )
        assertTrue(escaped is ToolExecutorResult.Failed)

        val rootProcessId = requireNotNull(access.rootServiceProcessIdForTest())
        assertTrue(Shell.cmd("kill -9 $rootProcessId").exec().isSuccess)
        awaitAccess { it.grant == RootGrantState.LOST }
        val afterCrash = execute(RootTools.PROCESS_LIST)
        assertTrue(afterCrash is ToolExecutorResult.Failed)
        assertEquals(RootSessionState.LOST, sessions.status().state)
    }

    private fun completed(
        name: String,
        vararg args: Pair<String, JsonPrimitive>,
    ): JsonObject = (execute(name, *args) as ToolExecutorResult.Completed).output.jsonObject

    private fun execute(
        name: String,
        vararg args: Pair<String, JsonPrimitive>,
    ): ToolExecutorResult =
        tools.executor(name).execute(
            ExecutableToolCall(
                "hxa095-$name",
                name,
                "1",
                buildJsonObject { args.forEach { (key, value) -> put(key, value) } },
                ExecutionTargetType.LOCAL_ROOT,
                clock.now().plusSeconds(30),
                NoCancellation,
            ),
        )

    private fun awaitAccess(condition: (RootAccessStatus) -> Boolean): RootAccessStatus {
        val deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS
        var status = access.status()
        while (!condition(status) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MS)
            status = access.status()
        }
        assertTrue("timed out with $status", condition(status))
        return status
    }

    private companion object {
        const val EXPECTED_ROOT_ARGUMENT = "hxa095ExpectedRoot"
        const val SYSTEM_ETC_SCOPE = "system-etc"
        const val WAIT_TIMEOUT_MS = 30_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
