package com.helix.feature.browser

import android.os.SystemClock
import android.view.autofill.AutofillManager
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.clickNode
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.evaluate
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.instrumentation
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.onMain
import com.helix.feature.browser.BrowserOwnerDeviceTest.Companion.waitNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BrowserAutofillDeviceTest {
    @Test
    fun systemServiceFillsAndSavesTheActivityWebForm() {
        val oldService = shell("settings get secure autofill_service").trim()
        val component = "${instrumentation.targetContext.packageName}/com.helix.feature.browser.FixtureAutofillService"
        FixtureAutofillService.filled = CountDownLatch(1)
        FixtureAutofillService.saved = CountDownLatch(1)
        FixtureAutofillService.savedValues = emptyList()
        shell("settings put secure autofill_service $component")
        try {
            withFormServer { url -> verifyFillAndSave(url) }
        } finally {
            if (oldService.isEmpty() ||
                oldService == "null"
            ) {
                shell("settings delete secure autofill_service")
            } else {
                shell("settings put secure autofill_service $oldService")
            }
        }
    }

    private fun verifyFillAndSave(url: String) {
        BrowserActivityFixture().use { fixture ->
            verifyForm(fixture, url)
            fixture.scenario.recreate()
            verifyForm(fixture, url)
            shell("settings delete secure autofill_service")
            val deadline = SystemClock.uptimeMillis() + 10_000
            var enabled = true
            while (enabled && SystemClock.uptimeMillis() < deadline) {
                fixture.scenario.onActivity { enabled = it.getSystemService(AutofillManager::class.java).isEnabled }
                if (enabled) SystemClock.sleep(20)
            }
            org.junit.Assert.assertFalse("system service revocation reaches the live Activity", enabled)
        }
    }

    private fun verifyForm(
        fixture: BrowserActivityFixture,
        url: String,
    ) {
        FixtureAutofillService.filled = CountDownLatch(1)
        FixtureAutofillService.saved = CountDownLatch(1)
        FixtureAutofillService.savedValues = emptyList()
        lateinit var controller: BrowserController
        fixture.scenario.onActivity { controller = it.controller }
        val id = onMain { controller.openTab(url).tabId }
        fixture.scenario.onActivity { it.setContentView(controller.hostView(id)) }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (controller.tab(id)!!.isLoading && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
        }
        val view = onMain { controller.hostView(id)!! }
        focusInput(view)
        assertEquals("\"username\"", evaluate(view, "document.activeElement.id"))
        commitInput(view, "f")
        assertTrue("real AutofillService request", FixtureAutofillService.filled.await(15, TimeUnit.SECONDS))
        clickNode { it.text?.toString() == "Helix fixture account" }
        assertEquals("\"fixture-user\"", awaitValue(view) { it == "\"fixture-user\"" })
        focusInput(view)
        assertEquals("\"username\"", evaluate(view, "document.activeElement.id"))
        commitInput(view, "-edited")
        val edited = awaitValue(view) { it.contains("edited") }
        assertTrue("edited DOM value: $edited", edited.contains("edited"))
        evaluate(view, "document.querySelector('button').click()")
        val submitted = awaitValue(view, "document.body.innerText") { it.contains("Fixture signed in") }
        assertTrue("form reached its success page: $submitted", submitted.contains("Fixture signed in"))
        clickNode {
            it.viewIdResourceName?.endsWith("autofill_save_yes") == true ||
                it.text?.toString()?.equals("save", true) == true
        }
        assertTrue("real AutofillService save", FixtureAutofillService.saved.await(10, TimeUnit.SECONDS))
        assertTrue(
            "saved values: ${FixtureAutofillService.savedValues}",
            FixtureAutofillService.savedValues.any {
                it.contains("edited")
            },
        )
    }

    /** Exercise WebView's real IME entry point without depending on the emulator keyboard's composition. */
    private fun commitInput(
        view: android.webkit.WebView,
        value: String,
    ) {
        onMain {
            val connection = view.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            assertTrue("WebView input connection", connection != null)
            assertTrue("WebView input commit", connection!!.commitText(value, 1))
        }
    }

    private fun awaitValue(
        view: android.webkit.WebView,
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

    private fun focusInput(view: android.webkit.WebView) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!onMain { view.hasWindowFocus() } && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
        }
        assertTrue("Activity regained input focus after system Autofill UI", onMain { view.hasWindowFocus() })
        onMain { view.requestFocus() }
        val node =
            waitNode {
                it.className?.toString() == "android.widget.EditText" && !it.isPassword &&
                    it.packageName?.toString() == instrumentation.targetContext.packageName
            }
        assertTrue(
            "focus actual HTML input",
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS),
        )
        assertTrue(
            "click actual HTML input",
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),
        )
        instrumentation.waitForIdleSync()
    }

    private fun withFormServer(block: (String) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) { serve(server) }
            try {
                block("http://127.0.0.1:${server.localPort}/")
            } finally {
                server.close()
                worker.join(1000)
            }
        }
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            val socket =
                try {
                    server.accept()
                } catch (_: java.net.SocketException) {
                    break
                }
            socket.use {
                val reader = it.getInputStream().bufferedReader()
                val requestLine = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                val form =
                    "<html><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                        "<form><input name='username' id='username' autocomplete='username' " +
                        "style='margin:40px;width:220px;height:50px;font-size:20px'>" +
                        "<input type='password' name='password' autocomplete='current-password'>" +
                        "<button type='submit'>Submit fixture</button></form></html>"
                val html =
                    if (requestLine.contains(
                            "username=",
                        )
                    ) {
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
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(descriptor)
                .bufferedReader()
                .readText()
        }
}
