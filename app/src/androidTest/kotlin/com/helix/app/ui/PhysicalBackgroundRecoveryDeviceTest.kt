package com.helix.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.foreground.DataSyncForegroundService
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.FileInputStream

/** Opt-in real HOME/screen-off transitions, only in a disposable consumer installation. */
class PhysicalBackgroundRecoveryDeviceTest {
    @Test fun chatCompletesAfterHomeAndReturn() = exercise(false, false)

    @Test fun chatCompletesAfterScreenOffAndUnlock() = exercise(false, true)

    @Test fun goalPausesAndExplicitlyResumesAfterScreenOff() = exercise(true, true)

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One bounded real-platform fixture with unconditional cleanup.
    private fun exercise(goal: Boolean, lock: Boolean) =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assumeTrue(InstrumentationRegistry.getArguments().getString("helix.physical.recovery") == "true")
            check(context.packageName == "com.helix.agent") { "Disposable consumer sandbox required" }
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            check(power.isInteractive && !keyguard.isKeyguardLocked) { "Unlock phone before starting" }
            val container = (context.applicationContext as HelixApplication).appContainer
            container.firstLaunch.markSeen()
            // Keep the test-owned Activity through JUnit result delivery. The host uninstalls the
            // disposable package afterwards; ActivityScenario teardown is not lock-screen-safe.
            val hostActivity =
                instrumentation.startActivitySync(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Physical recovery fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                            CleartextAuthorization("127.0.0.1", server.port),
                            emptyList(),
                        ),
                        null,
                        cleartextConfirmed = true,
                    )
                try {
                    check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                    server.forceTextResponses = true
                    val session = chat.createSession("Physical recovery", provider, "fixture-model-a")
                    chat.openSession(session)
                    waitFor { chat.screen.value.openSessionId == session }
                    chat.setMode(if (goal) AgentMode.GOAL else AgentMode.CHAT)
                    server.holdChatStreams.set(true)
                    val goalId =
                        if (goal) {
                            chat.createGoal(
                                "Physical recovery",
                                listOf("Evidence"),
                                GoalBudgets(10, 10, 100000, 600000, 300000, 0),
                            )
                        } else {
                            null
                        }
                    if (goalId ==
                        null
                    ) {
                        chat.send("Physical recovery input")
                    } else {
                        chat.continueGoal(goalId, "Physical recovery input")
                    }
                    waitFor { server.heldStreams.get() == 1 && DataSyncForegroundService.runningInstance.get() != null }
                    val turn = storage.turns.listBySession(session).single()
                    shell(if (lock) "input keyevent KEYCODE_SLEEP" else "input keyevent KEYCODE_HOME")
                    if (lock) {
                        waitFor { !power.isInteractive }
                    } else {
                        waitFor { !hostActivity.hasWindowFocus() }
                    }
                    phase(
                        "background_observed interactive=${power.isInteractive} keyguard=${keyguard.isKeyguardLocked}",
                    )
                    SystemClock.sleep(3000)
                    assertTrue(
                        "Foreground service must survive transition",
                        DataSyncForegroundService.runningInstance.get() != null,
                    )
                    assertEquals("No duplicate provider request", 1, server.heldStreams.get())
                    assertTrue(chat.backgroundTasks.value.any { it.id == turn.id && it.running })
                    if (goalId == null) {
                        server.heldSocket.get().getOutputStream().apply {
                            write(
                                (
                                    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" recovered\"}," +
                                        "\"finish_reason\":null}]}\n\n" +
                                        "data: {\"choices\":[{\"index\":0,\"delta\":{}," +
                                        "\"finish_reason\":\"stop\"}]}\n\n" +
                                        "data: [DONE]\n\n"
                                ).toByteArray(),
                            )
                            flush()
                        }
                        server.heldSocket.get().shutdownOutput()
                        waitFor { storage.turns.resolve(turn.id).state == "COMPLETED" }
                    } else {
                        chat.stopTask(turn.id, pause = true)
                        waitFor { storage.goals.resolve(goalId).state == "PAUSED" }
                        assertEquals(
                            "USER_PAUSED",
                            storage.goalRuns
                                .listByGoal(goalId)
                                .single()
                                .outcome,
                        )
                    }
                    phase("task_settled_while_backgrounded")
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    // A secure keyguard requires the owner's normal unlock; never disable or bypass it.
                    waitFor(120000) { power.isInteractive && !keyguard.isKeyguardLocked }
                    shell("am start -W -f 0x10020000 -n com.helix.agent/com.helix.app.MainActivity")
                    waitFor { mainWindowResumed() }
                    phase("foreground_restored")
                    assertEquals(session, chat.screen.value.openSessionId)
                    assertEquals(1, storage.turns.listBySession(session).size)
                    if (goalId != null) {
                        chat.continueGoal(goalId, "Explicit resume")
                        waitFor { server.heldStreams.get() == 2 }
                        val resumed = storage.turns.listBySession(session).last()
                        assertTrue(resumed.id != turn.id)
                        chat.stopTask(resumed.id, pause = true)
                        waitFor { storage.goals.resolve(goalId).state == "PAUSED" }
                        assertEquals(2, storage.goalRuns.listByGoal(goalId).size)
                    }
                } finally {
                    chat.backgroundTasks.value
                        .filter { it.running }
                        .forEach { chat.stopTask(it.id) }
                    waitFor { chat.backgroundTasks.value.none { it.running } }
                    phase("cleanup")
                    shell("input keyevent KEYCODE_WAKEUP")
                    chat.closeSession()
                    container.providerService.delete(provider)
                }
            }
        }

    private fun phase(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            android.os.Bundle().apply {
                putString("helix.physical.phase", value)
            },
        )
    }

    private fun mainWindowResumed(): Boolean {
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed =
                ActivityLifecycleMonitorRegistry
                    .getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .any { it is MainActivity && it.hasWindowFocus() }
        }
        return resumed
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }

    private fun waitFor(
        timeout: Long = 10000,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Physical lifecycle condition timed out", predicate())
    }
}
