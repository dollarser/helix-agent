package com.helix.feature.browser

import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.clickNode
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.evaluate
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.instrumentation
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.onMain
import com.helix.feature.browser.snapshot.SnapshotResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * EV-02 (U2) long-stability soak — the device-side half of the browser/Autofill contract. One
 * instrumentation run drives the activity-owned browser through the plan section-4 fixed mixed
 * workload: a warmup phase, then active/idle hour-blocks (each hour = activeSecondsPerHour of
 * active cycles + idleSecondsPerHour of natural idle), then a cooldown observe phase.
 *
 * Every active cycle uses the real BrowserController / Activity owner / WebView and does, at
 * minimum: navigation to a unique local page (real request + DOM consistency), one snapshot read,
 * one non-sensitive button action, a temporary second tab switch-back-close, a real system
 * AutofillService fill + real-input edit + submit + real save (service-on) or a real-input manual
 * fill + submit with no system callback (service-off), a periodic background/foreground toggle on
 * the completed form, and a periodic Activity recreation (never a process kill) — then closes its
 * page and records a process-level FD/thread/PSS snapshot.
 *
 * One instrumentation, one target process for the entire run: the Activity may be recreated (a
 * counted workload item) but the loop never restarts the target process or per-Activity controller
 * per cycle — restarting to "reset" resources would mask a leak. The controller is re-read from the
 * live Activity at the top of every cycle so it transparently tracks recreations.
 *
 * Scheduling is by the device monotonic clock. Active cycles sit on a fixed cycleSeconds grid: a
 * late cycle is never accelerated nor skipped (it simply holds its slot). Each cycle's workload is
 * bounded to cycleMaxSeconds; exceeding it is a functional timeout failure, not a held schedule.
 * Idle phases keep the Activity and process alive, close all temporary pages, and emit a low-cost
 * heartbeat every heartbeatSeconds so the host watchdog does not mis-kill a quiet phase.
 *
 * The whole behavior is driven by instrumentation arguments (see [SoakConfig]); the formal 24h run,
 * the 2h paired pre-check and the short pilot differ ONLY in those values, never in code. The
 * service group sets the fixture AutofillService once and restores the previous setting in a
 * finally block.
 *
 * Structured device-side evidence (in the app external files dir; the host runner pulls it):
 * soak-manifest.json (config + pid + start), cycles.jsonl (one line per active cycle),
 * heartbeat.jsonl (one line per idle/cooldown heartbeat), requests.jsonl (one line per form-server
 * request), progress.json (atomic phase / liveness for the host watchdog), soak-done.json (marker).
 *
 * This is NOT the acceptance entry point on its own: scripts/run-browser-autofill-soak.py (host
 * runner — fixed APK identity/hash, PID/starttime/boot_id verification, system UID-proxy sampling,
 * watchdog, atomic writes, disk limits, forensics-first) launches it and owns the wait and the
 * pass/fail judgment. A short direct `am instrument` only exercises the device loop (a fixture
 * smoke), not a 24h result.
 */
@RunWith(AndroidJUnit4::class)
class BrowserAutofillSoakDeviceTest {
    /**
     * The fixture's frozen workload schedule (device-side half of the plan section-5 config). The
     * host-runner fields (serial / phase label / apkSha256 / configSha256) live on the runner side;
     * these are the workload-relevant values the device loop needs.
     */
    @Suppress("LongParameterList") // one constructor field per soak knob, all set from the -e args in from()
    private class SoakConfig(
        val runId: String,
        val warmupSeconds: Int,
        val durationSeconds: Int,
        val cooldownSeconds: Int,
        val cycleSeconds: Int,
        val cycleMaxSeconds: Int,
        val activeSecondsPerHour: Int,
        val idleSecondsPerHour: Int,
        val autofillMode: String,
        val recreateEvery: Int,
        val bgfgEvery: Int,
        val bgfgSeconds: Int,
        val heartbeatSeconds: Int,
    ) {
        companion object {
            fun from(args: Bundle): SoakConfig =
                SoakConfig(
                    runId = args.getString("helix.soak.runId") ?: "soak",
                    warmupSeconds = args.getString("helix.soak.warmupSeconds")?.toInt() ?: 0,
                    durationSeconds =
                        (
                            args.getString("helix.soak.durationSeconds")
                                ?: error("helix.soak.durationSeconds is required")
                        ).toInt(),
                    cooldownSeconds = args.getString("helix.soak.cooldownSeconds")?.toInt() ?: 0,
                    cycleSeconds = args.getString("helix.soak.cycleSeconds")?.toInt() ?: 120,
                    cycleMaxSeconds = args.getString("helix.soak.cycleMaxSeconds")?.toInt() ?: 112,
                    activeSecondsPerHour = args.getString("helix.soak.activeSecondsPerHour")?.toInt() ?: 3000,
                    idleSecondsPerHour = args.getString("helix.soak.idleSecondsPerHour")?.toInt() ?: 600,
                    autofillMode = args.getString("helix.soak.autofillMode") ?: "on",
                    recreateEvery = args.getString("helix.soak.recreateEvery")?.toInt() ?: 10,
                    bgfgEvery = args.getString("helix.soak.bgfgEvery")?.toInt() ?: 5,
                    bgfgSeconds = args.getString("helix.soak.bgfgSeconds")?.toInt() ?: 30,
                    heartbeatSeconds = args.getString("helix.soak.heartbeatSeconds")?.toInt() ?: 30,
                )
        }
    }

    // Global cycle identity (unique across warmup + active) and per-phase counters. The bgfg /
    // recreate cadences run on the PHASE-local counter so the formal counts stay exact (120 bgfg
    // and 60 recreations over 600 formal active cycles, not drifted by warmup cycles).
    private val globalSeq = AtomicInteger(0)
    private val warmupCount = AtomicInteger(0)
    private val formalCount = AtomicInteger(0)
    private val warmupCadence = AtomicInteger(0)
    private val activeCadence = AtomicInteger(0)
    private val lastActiveMonotonicMs = AtomicLong(0)
    private val lastHeartbeatMonotonicMs = AtomicLong(0)

    private lateinit var logDir: File
    private lateinit var cyclesLog: File
    private lateinit var heartbeatLog: File
    private lateinit var requestsLog: File
    private lateinit var progressFile: File

    private var cycleDeadlineMs = 0L
    private var diagnosticStage = "starting"
    private var diagnosticView: WebView? = null

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod") // one driver: warmup, active/idle hour blocks, cooldown
    fun continuousAutofillSoak() {
        val cfg = SoakConfig.from(InstrumentationRegistry.getArguments())
        require(cfg.durationSeconds > 0) { "durationSeconds must be > 0: ${cfg.durationSeconds}" }
        require(cfg.autofillMode == "on" || cfg.autofillMode == "off") {
            "autofillMode must be on/off: ${cfg.autofillMode}"
        }
        require(cfg.warmupSeconds in 0..86_400) { "warmupSeconds out of range: ${cfg.warmupSeconds}" }
        require(cfg.cooldownSeconds in 0..86_400) { "cooldownSeconds out of range: ${cfg.cooldownSeconds}" }
        require(cfg.cycleSeconds in 1..3600) { "cycleSeconds out of range: ${cfg.cycleSeconds}" }
        require(cfg.cycleMaxSeconds in 1..3600) { "cycleMaxSeconds out of range: ${cfg.cycleMaxSeconds}" }
        require(cfg.cycleMaxSeconds <= cfg.cycleSeconds) {
            "cycleMaxSeconds (${cfg.cycleMaxSeconds}) must be <= cycleSeconds (${cfg.cycleSeconds})"
        }
        require(cfg.activeSecondsPerHour in 0..86_400) {
            "activeSecondsPerHour out of range: ${cfg.activeSecondsPerHour}"
        }
        require(cfg.idleSecondsPerHour in 0..86_400) { "idleSecondsPerHour out of range: ${cfg.idleSecondsPerHour}" }
        require(cfg.activeSecondsPerHour + cfg.idleSecondsPerHour > 0) {
            "activeSecondsPerHour + idleSecondsPerHour must be > 0"
        }
        if (cfg.activeSecondsPerHour > 0) {
            require(cfg.activeSecondsPerHour >= cfg.cycleSeconds) {
                "activeSecondsPerHour (${cfg.activeSecondsPerHour}) must fit a ${cfg.cycleSeconds}s slot"
            }
        }
        require(cfg.recreateEvery in 1..100_000) { "recreateEvery out of range: ${cfg.recreateEvery}" }
        require(cfg.bgfgEvery in 1..100_000) { "bgfgEvery out of range: ${cfg.bgfgEvery}" }
        require(cfg.bgfgSeconds in 0..3600) { "bgfgSeconds out of range: ${cfg.bgfgSeconds}" }
        require(cfg.heartbeatSeconds in 1..3600) { "heartbeatSeconds out of range: ${cfg.heartbeatSeconds}" }

        logDir =
            instrumentation.targetContext.getExternalFilesDir(null)
                ?: instrumentation.targetContext.filesDir
        logDir.mkdirs()
        cyclesLog = File(logDir, "cycles.jsonl").apply { delete() }
        heartbeatLog = File(logDir, "heartbeat.jsonl").apply { delete() }
        requestsLog = File(logDir, "requests.jsonl").apply { delete() }
        progressFile = File(logDir, "progress.json")
        File(logDir, "soak-done.json").delete()
        File(logDir, "autofill-failure.json").delete()
        // Clear any prior-run liveness file: the host watchdog reads progress.json, and a stale one
        // from an earlier run on this device would look like a stalled run.
        progressFile.delete()

        val oldService = shell("settings get secure autofill_service").trim()
        val component =
            "${instrumentation.targetContext.packageName}/com.helix.feature.browser.FixtureAutofillService"
        if (cfg.autofillMode == "on") shell("settings put secure autofill_service $component")
        val requests = AtomicInteger(0)
        val startedMs = SystemClock.elapsedRealtime()
        updateProgress(cfg, "starting", 0)
        File(logDir, "soak-manifest.json").writeText(
            "{\"runId\":\"${jsonEscape(cfg.runId)}\",\"warmupSeconds\":${cfg.warmupSeconds}," +
                "\"durationSeconds\":${cfg.durationSeconds},\"cooldownSeconds\":${cfg.cooldownSeconds}," +
                "\"cycleSeconds\":${cfg.cycleSeconds},\"cycleMaxSeconds\":${cfg.cycleMaxSeconds}," +
                "\"activeSecondsPerHour\":${cfg.activeSecondsPerHour}," +
                "\"idleSecondsPerHour\":${cfg.idleSecondsPerHour}," +
                "\"autofillMode\":\"${cfg.autofillMode}\",\"recreateEvery\":${cfg.recreateEvery}," +
                "\"bgfgEvery\":${cfg.bgfgEvery},\"bgfgSeconds\":${cfg.bgfgSeconds}," +
                "\"heartbeatSeconds\":${cfg.heartbeatSeconds}," +
                "\"pid\":${Process.myPid()},\"startedMonotonicMs\":$startedMs}\n",
        )
        try {
            withFormServer(requests) { baseUrl ->
                BrowserActivityFixture().use { fixture ->
                    // (A) warmup — same path, counted separately, not part of the formal total.
                    if (cfg.warmupSeconds > 0) {
                        val wStart = SystemClock.elapsedRealtime()
                        runActiveBlock(
                            fixture,
                            cfg,
                            baseUrl,
                            "warmup",
                            warmupCount,
                            warmupCadence,
                            wStart,
                            wStart + cfg.warmupSeconds * 1000L,
                        )
                    }
                    // (B) active/idle hour-blocks for the formal duration.
                    val durStart = SystemClock.elapsedRealtime()
                    val durEnd = durStart + cfg.durationSeconds * 1000L
                    var blockStart = durStart
                    while (blockStart < durEnd) {
                        val activeEnd = minOf(blockStart + cfg.activeSecondsPerHour * 1000L, durEnd)
                        if (activeEnd > blockStart) {
                            runActiveBlock(
                                fixture,
                                cfg,
                                baseUrl,
                                "active",
                                formalCount,
                                activeCadence,
                                blockStart,
                                activeEnd,
                            )
                        }
                        val idleEnd = minOf(activeEnd + cfg.idleSecondsPerHour * 1000L, durEnd)
                        if (cfg.idleSecondsPerHour > 0 && idleEnd > activeEnd) runIdle(cfg, "idle", idleEnd)
                        val next = maxOf(idleEnd, blockStart + 1)
                        if (next <= blockStart) break
                        blockStart = next
                    }
                    // (C) cooldown — natural-recycling observation, same shape as idle.
                    if (cfg.cooldownSeconds > 0) {
                        val cStart = SystemClock.elapsedRealtime()
                        runIdle(cfg, "cooldown", cStart + cfg.cooldownSeconds * 1000L)
                    }
                }
            }
            updateProgress(cfg, "done", globalSeq.get())
            File(logDir, "soak-done.json").writeText(
                "{\"runId\":\"${jsonEscape(cfg.runId)}\",\"warmupCycles\":${warmupCount.get()}," +
                    "\"formalCycles\":${formalCount.get()},\"requests\":${requests.get()}," +
                    "\"pid\":${Process.myPid()},\"doneMonotonicMs\":${SystemClock.elapsedRealtime()}," +
                    "\"durationMs\":${SystemClock.elapsedRealtime() - startedMs}}\n",
            )
        } finally {
            if (cfg.autofillMode == "on") restoreAutofillService(oldService)
        }
    }

    /**
     * Runs active cycles on a fixed cycleSeconds grid inside [blockStart, blockEnd). Only slots
     * that FULLY complete within the block are run (a trailing partial slot is left, so a 900s
     * active window at 120s yields exactly 7 complete slots). A late cycle is never accelerated or
     * skipped: each slot starts at its grid point and the following slot holds its own grid point.
     */
    @Suppress("LongParameterList") // block drives per-cycle cadence (count/elapsed windows) the fixture must observe
    private fun runActiveBlock(
        fixture: BrowserActivityFixture,
        cfg: SoakConfig,
        baseUrl: String,
        phase: String,
        countSink: AtomicInteger,
        cadence: AtomicInteger,
        blockStart: Long,
        blockEnd: Long,
    ) {
        updateProgress(cfg, phase, globalSeq.get())
        val slotMs = cfg.cycleSeconds * 1000L
        var i = 0
        while (true) {
            val slotStart = blockStart + i * slotMs
            val slotEnd = slotStart + slotMs
            if (slotEnd > blockEnd) break
            val now = SystemClock.elapsedRealtime()
            if (now < slotStart) SystemClock.sleep(slotStart - now)
            val seq = globalSeq.incrementAndGet()
            val local = cadence.incrementAndGet()
            countSink.incrementAndGet()
            updateProgress(cfg, phase, seq)
            runCycle(fixture, seq, local, cfg, baseUrl, phase)
            updateProgress(cfg, phase, seq)
            i++
        }
        // Hold the active window to its end: after the last grid slot the phase stays "active" (no
        // round running) until blockEnd, so the active and idle windows are not skewed. The max
        // gap between rounds is one slot (below the host active watchdog), so no heartbeat is needed.
        val ended = SystemClock.elapsedRealtime()
        if (ended < blockEnd) SystemClock.sleep(blockEnd - ended)
        updateProgress(cfg, phase, globalSeq.get())
    }

    /**
     * One active cycle (the full section-4 workload). No slot-hold here — the grid hold is the
     * scheduler's job. The Activity recreation is the last workload step (it replaces the
     * controller, which the next cycle re-reads) and is still inside the cycleMaxSeconds bound.
     */
    private fun runCycle(
        fixture: BrowserActivityFixture,
        seq: Int,
        local: Int,
        cfg: SoakConfig,
        baseUrl: String,
        phase: String,
    ) {
        cycleDeadlineMs = SystemClock.elapsedRealtime() + cfg.cycleMaxSeconds * 1000L
        diagnosticStage = "openPage"
        try {
            runCycleBody(fixture, seq, local, cfg, baseUrl, phase)
        } catch (failure: Throwable) {
            try {
                AutofillFailureEvidence.capture(
                    File(logDir, "autofill-failure.json"),
                    cfg.runId,
                    seq,
                    diagnosticStage,
                    fixture.scenario.state.name,
                    diagnosticView,
                    failure,
                )
            } catch (captureFailure: Exception) {
                failure.addSuppressed(captureFailure)
                Log.w(TAG, "Autofill failure evidence unavailable: ${captureFailure.javaClass.simpleName}")
            }
            throw failure
        } finally {
            diagnosticView = null
        }
    }

    @Suppress("LongMethod") // one cycle: open, snapshot/action, form, bgfg, close, recreate; kept linear
    private fun runCycleBody(
        fixture: BrowserActivityFixture,
        seq: Int,
        local: Int,
        cfg: SoakConfig,
        baseUrl: String,
        phase: String,
    ) {
        val cycleStart = SystemClock.elapsedRealtime()
        val steps = mutableListOf<String>()
        val flags = mutableMapOf<String, Boolean>()
        // Re-read the controller each cycle: an Activity recreation replaces it, and the loop must
        // not depend on a controller captured before a recreate.
        lateinit var controller: BrowserController
        fixture.scenario.onActivity { controller = it.controller }
        // Reset the fixture-service latches so each cycle's fill/save is judged on its own callbacks.
        FixtureAutofillService.filled = CountDownLatch(1)
        FixtureAutofillService.saved = CountDownLatch(1)
        FixtureAutofillService.savedValues = emptyList()

        // (1) open a unique local page for this cycle and make it the content view.
        val url = "$baseUrl?run=${jsonEscape(cfg.runId)}&cycle=$seq"
        val openResult = onMain { controller.openTab(url) }
        assertEquals(
            "cycle $seq page was admitted by the URL policy",
            null,
            openResult.failureReason,
        )
        val id = openResult.tabId
        // onActivity's lambda already runs on the main thread, so the host view is read directly —
        // wrapping it in onMain (runOnMainSync) would throw "cannot be called from the main thread".
        fixture.scenario.onActivity { activity ->
            activity.setContentView(controller.hostView(id)!!)
        }
        waitLoaded(controller, id)
        val view = onMain { controller.hostView(id)!! }
        diagnosticView = view
        steps += step("openPage", cycleStart)

        // (2) read one snapshot, do one non-sensitive action, open a second tab, switch back, close it.
        val snapLatch = CountDownLatch(1)
        var snapSuccess = false
        onMain {
            controller.snapshot(id) { result ->
                snapSuccess = result is SnapshotResult.Success
                snapLatch.countDown()
            }
        }
        assertTrue("snapshot callback fired (cycle $seq)", snapLatch.await(10, TimeUnit.SECONDS))
        flags["snapshotSuccess"] = snapSuccess
        evaluate(view, "document.getElementById('ping').click(); 'pinged'")
        assertEquals("\"pong\"", evaluate(view, "document.title"))
        onMain {
            val second = controller.newTab()
            controller.select(second)
            controller.select(id)
            controller.closeTab(second)
        }
        assertEquals("temp tab must not disturb the current page", "\"pong\"", evaluate(view, "document.title"))
        steps += step("snapshotAction", cycleStart)

        // (3)/(4) the form workload: real autofill fill + edit + submit + save (on), or a real-input
        // manual fill + submit with no system callback (off).
        diagnosticStage = "form-${cfg.autofillMode}"
        when (cfg.autofillMode) {
            "on" -> runFillAndSave(view, seq, steps, cycleStart)
            else -> runManualSubmit(view, seq, steps, cycleStart)
        }

        // (5) periodic background/foreground toggle on the completed form; the same View is reused.
        if (cfg.bgfgSeconds > 0 && local % cfg.bgfgEvery == 0) {
            val before = onMain { controller.hostView(id)!! }
            diagnosticStage = "background"
            fixture.scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(cfg.bgfgSeconds * 1000L)
            diagnosticStage = "foreground"
            fixture.scenario.moveToState(Lifecycle.State.RESUMED)
            assertSame(
                "background/foreground reuses the same View (cycle $seq)",
                before,
                onMain { controller.hostView(id) },
            )
            steps += step("bgfg", cycleStart)
        }

        // (7) close this cycle's page and confirm none is left open. Metrics below come from /proc,
        // never from a WebView created just to read state.
        diagnosticStage = "closePage"
        val closeStart = SystemClock.elapsedRealtime()
        onMain { controller.closeTab(id) }
        assertEquals(
            "cycle $seq closed its page",
            0,
            onMain { controller.state.value.tabs.size },
        )
        steps += step("closePage", closeStart)

        // Per-cycle resource snapshot (process-level) + structured log line.
        val fd = File("/proc/self/fd").list()?.size ?: -1
        val threads = File("/proc/self/task").list()?.size ?: -1
        val pssKb = Debug.getPss()
        cyclesLog.appendText(cycleJson(cfg, seq, local, phase, cycleStart, steps, flags, fd, threads, pssKb))
        Log.i(
            TAG,
            "cycle=$seq phase=$phase pid=${Process.myPid()} fd=$fd threads=$threads pssKb=$pssKb " +
                "elapsedMs=${SystemClock.elapsedRealtime() - cycleStart} steps=${steps.size} " +
                "snapshotSuccess=$snapSuccess",
        )

        // (6) periodic Activity recreation (NOT a process kill) after this cycle's side effects are
        // confirmed; the next cycle re-reads the fresh controller.
        if (local % cfg.recreateEvery == 0) {
            diagnosticStage = "recreate"
            fixture.scenario.recreate()
            Log.i(TAG, "activity recreated after cycle=$seq (phase=$phase)")
        }

        // Bound: the per-cycle workload — including the recreate transition — must stay under the
        // frozen max. A slow round is a functional timeout failure, not a silently held schedule.
        val workloadMs = SystemClock.elapsedRealtime() - cycleStart
        assertTrue(
            "cycle $seq workload within ${cfg.cycleMaxSeconds}s max (took ${workloadMs}ms)",
            workloadMs <= cfg.cycleMaxSeconds * 1000L,
        )
    }

    /**
     * Idle / cooldown: keep the Activity and process alive (no workload, no Autofill probing), and
     * emit a low-cost heartbeat every heartbeatSeconds so the host watchdog does not mis-kill a
     * quiet phase. Temporary pages are already closed at the end of each active cycle.
     */
    private fun runIdle(
        cfg: SoakConfig,
        phase: String,
        idleEnd: Long,
    ) {
        val hbMs = cfg.heartbeatSeconds * 1000L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            heartbeatLog.appendText(
                "{\"monotonicMs\":$now,\"pid\":${Process.myPid()},\"phase\":\"$phase\"}\n",
            )
            updateProgress(cfg, phase, globalSeq.get(), lastHeartbeat = now)
            if (now >= idleEnd) break
            SystemClock.sleep(minOf(hbMs, idleEnd - now))
        }
    }

    /** Atomically publish current phase + liveness for the host watchdog (temp file + rename). */
    private fun updateProgress(
        cfg: SoakConfig,
        phase: String,
        seq: Int,
        lastHeartbeat: Long? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (phase == "warmup" || phase == "active") lastActiveMonotonicMs.set(now)
        if (lastHeartbeat != null) lastHeartbeatMonotonicMs.set(lastHeartbeat)
        val json =
            "{\"runId\":\"${jsonEscape(cfg.runId)}\",\"phase\":\"$phase\",\"seq\":$seq," +
                "\"pid\":${Process.myPid()},\"warmupCycles\":${warmupCount.get()}," +
                "\"formalCycles\":${formalCount.get()}," +
                "\"lastActiveMonotonicMs\":${lastActiveMonotonicMs.get()}," +
                "\"lastHeartbeatMonotonicMs\":${lastHeartbeatMonotonicMs.get()}," +
                "\"updatedMonotonicMs\":$now}\n"
        val tmp = File(logDir, "progress.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(progressFile)) {
            progressFile.delete()
            tmp.renameTo(progressFile)
        }
    }

    /** Real system AutofillService fill + real-input edit + submit + real system save (service-on group). */
    private fun runFillAndSave(
        view: WebView,
        cycle: Int,
        steps: MutableList<String>,
        cycleStart: Long,
    ) {
        focusInput(view)
        assertEquals("\"username\"", evaluate(view, "document.activeElement.id"))
        commitInput(view, "f")
        assertTrue(
            "real AutofillService fill request (cycle $cycle)",
            FixtureAutofillService.filled.await(15, TimeUnit.SECONDS),
        )
        clickNode { it.text?.toString() == "Helix fixture account" }
        assertEquals("\"fixture-user\"", awaitValue(view) { it == "\"fixture-user\"" })
        diagnosticStage = "edit-after-autofill"
        focusInput(view)
        commitInput(view, "-edited")
        val edited = awaitValue(view) { it.contains("edited") }
        assertTrue("edited DOM value (cycle $cycle): $edited", edited.contains("edited"))
        evaluate(view, "document.querySelector('button[type=submit]').click(); 'sent'")
        val submitted = awaitValue(view, "document.body.innerText") { it.contains("Fixture signed in") }
        assertTrue("form reached its success page (cycle $cycle): $submitted", submitted.contains("Fixture signed in"))
        diagnosticStage = "system-save-dialog"
        clickNode {
            it.viewIdResourceName?.endsWith("autofill_save_yes") == true ||
                it.text?.toString()?.equals("save", true) == true
        }
        assertTrue(
            "real AutofillService save (cycle $cycle)",
            FixtureAutofillService.saved.await(10, TimeUnit.SECONDS),
        )
        assertTrue(
            "saved values carried this cycle's edit (cycle $cycle): ${FixtureAutofillService.savedValues}",
            FixtureAutofillService.savedValues.any { it.contains("edited") },
        )
        steps += step("form", cycleStart)
    }

    /** Real-input manual fill + submit, asserting the system fill/save callbacks never fired (service-off group). */
    private fun runManualSubmit(
        view: WebView,
        cycle: Int,
        steps: MutableList<String>,
        cycleStart: Long,
    ) {
        focusInput(view)
        commitInput(view, "manual-$cycle")
        val filled = awaitValue(view) { it.contains("manual-$cycle") }
        assertTrue("manual value committed (cycle $cycle): $filled", filled.contains("manual-$cycle"))
        evaluate(view, "document.querySelector('button[type=submit]').click(); 'sent'")
        val submitted = awaitValue(view, "document.body.innerText") { it.contains("Fixture signed in") }
        assertTrue("form reached its success page (cycle $cycle): $submitted", submitted.contains("Fixture signed in"))
        assertFalse(
            "no AutofillService fill callback in off mode (cycle $cycle)",
            FixtureAutofillService.filled.await(0, TimeUnit.MILLISECONDS),
        )
        steps += step("form", cycleStart)
    }

    private fun waitLoaded(
        controller: BrowserController,
        id: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while ((controller.tab(id)?.isLoading != false) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
        }
        assertTrue("cycle page settled", controller.tab(id)?.isLoading == false)
    }

    /**
     * Commit text through the WebView's real `InputConnection` — the actual IME entry point that
     * also fires the autofill request.
     */
    private fun commitInput(
        view: WebView,
        value: String,
    ) {
        val deadline = minOf(cycleDeadlineMs, SystemClock.elapsedRealtime() + 10_000)
        var connection = onMain { view.onCreateInputConnection(android.view.inputmethod.EditorInfo()) }
        while (connection == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
            connection = onMain { view.onCreateInputConnection(android.view.inputmethod.EditorInfo()) }
        }
        val ready = requireNotNull(connection) { "WebView input connection readiness deadline" }
        onMain { assertTrue("WebView input commit", ready.commitText(value, 1)) }
    }

    private fun focusInput(view: WebView) {
        AutofillInputProbe.focus(view, cycleDeadlineMs)
    }

    private fun awaitValue(
        view: WebView,
        script: String = "document.getElementById('username').value",
        predicate: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var value = evaluate(view, script).orEmpty()
        while (!predicate(value) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
            value = evaluate(view, script).orEmpty()
        }
        return value
    }

    private fun step(
        name: String,
        sinceStart: Long,
    ): String = "{\"name\":\"$name\",\"elapsedMs\":${SystemClock.elapsedRealtime() - sinceStart}}"

    @Suppress("LongParameterList") // one parameter per cycle-record field
    private fun cycleJson(
        cfg: SoakConfig,
        seq: Int,
        local: Int,
        phase: String,
        cycleStart: Long,
        steps: List<String>,
        flags: Map<String, Boolean>,
        fd: Int,
        threads: Int,
        pssKb: Long,
    ): String =
        "{\"runId\":\"${jsonEscape(cfg.runId)}\",\"cycle\":$seq,\"local\":$local,\"phase\":\"$phase\"," +
            "\"monotonicMs\":$cycleStart,\"pid\":${Process.myPid()},\"fd\":$fd,\"threads\":$threads,\"pssKb\":$pssKb," +
            "\"steps\":$steps,\"flags\":{" +
            flags.entries.joinToString(",") { (k, v) -> "\"$k\":$v" } +
            "},\"status\":\"ok\"}\n"

    private fun restoreAutofillService(oldService: String) {
        if (oldService.isEmpty() || oldService == "null") {
            shell("settings delete secure autofill_service")
        } else {
            shell("settings put secure autofill_service $oldService")
        }
    }

    private fun withFormServer(
        requests: AtomicInteger,
        block: (String) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) { serve(server, requests) }
            try {
                block("http://127.0.0.1:${server.localPort}/")
            } finally {
                server.close()
                worker.join(1000)
            }
        }
    }

    @Suppress("NestedBlockDepth") // accept → parse → respond branches are inherently nested in a socket loop
    private fun serve(
        server: ServerSocket,
        requests: AtomicInteger,
    ) {
        while (!server.isClosed) {
            val socket =
                try {
                    server.accept()
                } catch (_: java.net.SocketException) {
                    break
                }
            try {
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                    requests.incrementAndGet()
                    requestsLog.appendText(
                        "{\"monotonicMs\":${SystemClock.elapsedRealtime()},\"line\":\"${jsonEscape(requestLine)}\"}\n",
                    )
                    Log.i(TAG, "form request: $requestLine")
                    val form =
                        "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body>" +
                            "<span id='status'></span>" +
                            "<button id='ping' type='button' " +
                            "onclick=\"document.title='pong';" +
                            "document.getElementById('status').innerText='pong'\">Ping fixture</button>" +
                            "<form><input name='username' id='username' autocomplete='username' " +
                            "style='margin:40px;width:220px;height:50px;font-size:20px'>" +
                            "<input type='password' name='password' autocomplete='current-password'>" +
                            "<button type='submit'>Submit fixture</button></form></body></html>"
                    val html =
                        if (requestLine.contains("username=")) {
                            "<html><body>Fixture signed in</body></html>"
                        } else {
                            form
                        }
                    val bytes = html.toByteArray()
                    val headers =
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().write(headers.toByteArray())
                    it.getOutputStream().write(bytes)
                }
            } catch (_: java.io.IOException) {
                // The WebView aborts a request (a focus transition, a navigation, a temp-tab close)
                // before the response is fully written; that broken pipe is routine and must not
                // crash the daemon server thread (an uncaught exception there kills the whole
                // instrumentation process).
            }
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .bufferedReader()
                .readText()
        }

    private fun jsonEscape(s: String): String =
        s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private companion object {
        const val TAG = "HelixAutofillSoak"
    }
}
