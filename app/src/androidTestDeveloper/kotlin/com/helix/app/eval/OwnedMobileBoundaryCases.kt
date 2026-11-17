package com.helix.app.eval

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.core.storage.entity.ToolCallEntity
import java.io.File

internal fun dynamicMobilePrompt(
    fixture: String,
    nonce: String,
): String =
    "Owned synthetic test. Operate only $fixture on the selected BACKGROUND display and this session workspace. " +
        "Observe first, launch the package if needed, and open its Open page. " +
        "Click Load delayed result exactly once. " +
        "Wait for the actual delayed result DYNAMIC_DONE:1, not DYNAMIC_LOADING, and verify it. " +
        "Then write and reread output/result.txt containing exactly $nonce. " +
        "No screenshots, scripts, source inspection, other apps or main-display actions."

internal fun mainReturnPrompt(
    fixture: String,
    host: String,
    nonce: String,
): String =
    "Owned synthetic test on the MAIN display. Observe this Helix conversation first. " +
        "Operate only $fixture and this session workspace, and return to this originating $host conversation. " +
        "Launch the fixture, fill Background text with $nonce and click Increment exactly once. " +
        "Verify BACKGROUND_COUNT:1 and the input. Use ordinary file tools to create output/result.txt " +
        "in this session workspace containing exactly $nonce, then reread it. This is not a fixture app file. " +
        "Return to this original Helix conversation and verify the return before finishing. " +
        "No screenshots, scripts, source inspection, other apps, settings changes or moving tasks between displays."

/** Instrumentation restarts the host process; restore only its own Activity after host-side preflight. */
internal suspend fun prepareOwnedMainReturn(app: HelixApplication) {
    check(InstrumentationRegistry.getArguments().getString("helixMainReturnPrechecked") == "true") {
        "Host must verify foreground Helix and no user activity before instrumentation starts"
    }
    val intent = requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
    app.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
    while (!mainHelixResumed(app) && android.os.SystemClock.elapsedRealtime() < deadline) {
        kotlinx.coroutines.delay(100)
    }
    check(mainHelixResumed(app)) { "Own Helix Activity did not resume; do not operate another app" }
}

private fun mainHelixResumed(app: HelixApplication): Boolean {
    var resumed = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        resumed =
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                .any { it is com.helix.app.MainActivity && it.packageName == app.packageName && it.hasWindowFocus() }
    }
    return resumed
}

internal fun verifyDynamicMobile(
    app: HelixApplication,
    c: AppContainer,
    calls: List<ToolCallEntity>,
    root: File,
    nonce: String,
): Boolean {
    val clicks =
        app.contentResolver
            .query(
                Uri.parse("content://${app.packageName}.test.capability-state/dynamic"),
                null,
                null,
                null,
                null,
            )!!
            .use { it.moveToFirst() && it.getInt(0) == 1 }
    return clicks && observed(c, calls, "DYNAMIC_DONE:1") && File(root, "output/result.txt").readText() == nonce
}

internal fun verifyMainReturn(
    app: HelixApplication,
    c: AppContainer,
    calls: List<ToolCallEntity>,
    root: File,
    nonce: String,
    session: String,
): Boolean {
    val effect =
        app.contentResolver
            .query(
                Uri.parse("content://${app.packageName}.test.capability-state/background"),
                null,
                null,
                null,
                null,
            )!!
            .use { it.moveToFirst() && it.getInt(0) == 0 && it.getInt(1) == 1 && it.getInt(2) == 0 }
    val returnCalls = calls.dropWhile { it.name != "ui_set_text" }.filter { it.name == "ui_snapshot" }
    return effect && observed(c, calls, "BACKGROUND_COUNT:1", nonce) &&
        observed(c, returnCalls, app.packageName, "Synthetic closed loop") && mainHelixResumed(app) &&
        c.chatService.screen.value.openSessionId == session && File(root, "output/result.txt").readText() == nonce
}

private fun observed(
    c: AppContainer,
    calls: List<ToolCallEntity>,
    vararg values: String,
): Boolean =
    calls
        .filter { it.state == "COMPLETED" && it.name in setOf("ui_snapshot", "ui_wait", "ui_click", "ui_click_match") }
        .any { call ->
            c.storage.toolResults.byToolCall(call.callId)?.let(c.storage.toolResults::readContent)?.let { content ->
                values.all { it in content }
            } == true
        }
