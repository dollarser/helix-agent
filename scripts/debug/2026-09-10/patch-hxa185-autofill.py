"""One-time Autofill fixture adaptation; no execution of the fixture or runner."""
from pathlib import Path
p=Path('feature/browser/src/androidTest/kotlin/com/helix/feature/browser/BrowserAutofillSoakDeviceTest.kt')
s=p.read_text().replace('import android.view.accessibility.AccessibilityNodeInfo\n','').replace('import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.waitNode\n','')
s=s.replace('    @Test\n    fun continuousAutofillSoak()', '''    private var cycleDeadlineMs = 0L
    private var diagnosticStage = "starting"
    private var diagnosticView: WebView? = null

    @Test
    fun continuousAutofillSoak()''')
s=s.replace('        File(logDir, "soak-done.json").delete()','        File(logDir, "soak-done.json").delete()\n        File(logDir, "autofill-failure.json").delete()')
needle='''        val cycleStart = SystemClock.elapsedRealtime()
        val steps = mutableListOf<String>()'''
replacement='''        cycleDeadlineMs = SystemClock.elapsedRealtime() + cfg.cycleMaxSeconds * 1000L
        diagnosticStage = "openPage"
        try {
            runCycleBody(fixture, seq, local, cfg, baseUrl, phase)
        } catch (failure: Throwable) {
            try {
                AutofillFailureEvidence.capture(
                    File(logDir, "autofill-failure.json"), cfg.runId, seq, diagnosticStage, diagnosticView, failure,
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

    private fun runCycleBody(
        fixture: BrowserActivityFixture,
        seq: Int,
        local: Int,
        cfg: SoakConfig,
        baseUrl: String,
        phase: String,
    ) {
        val cycleStart = SystemClock.elapsedRealtime()
        val steps = mutableListOf<String>()'''
assert needle in s;s=s.replace(needle,replacement,1)
s=s.replace('        val view = onMain { controller.hostView(id)!! }','        val view = onMain { controller.hostView(id)!! }\n        diagnosticView = view',1)
s=s.replace('        when (cfg.autofillMode) {','        diagnosticStage = "form-${cfg.autofillMode}"\n        when (cfg.autofillMode) {',1)
s=s.replace('            fixture.scenario.moveToState(Lifecycle.State.CREATED)','            diagnosticStage = "background"\n            fixture.scenario.moveToState(Lifecycle.State.CREATED)',1)
s=s.replace('            fixture.scenario.moveToState(Lifecycle.State.RESUMED)','            diagnosticStage = "foreground"\n            fixture.scenario.moveToState(Lifecycle.State.RESUMED)',1)
s=s.replace('        val closeStart = SystemClock.elapsedRealtime()','        diagnosticStage = "closePage"\n        val closeStart = SystemClock.elapsedRealtime()',1)
s=s.replace('        focusInput(view)\n        commitInput(view, "-edited")','        diagnosticStage = "edit-after-autofill"\n        focusInput(view)\n        commitInput(view, "-edited")')
s=s.replace('        clickNode {\n            it.viewIdResourceName','        diagnosticStage = "system-save-dialog"\n        clickNode {\n            it.viewIdResourceName',1)
a=s.index('        onMain { view.requestFocus() }',s.index('    private fun commitInput('));b=s.index('    private fun awaitValue(',a)
s=s[:a]+'''        val deadline = minOf(cycleDeadlineMs, SystemClock.elapsedRealtime() + 10_000)
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

'''+s[b:]
p.write_text(s)
