package com.helix.app.eval

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A crowded real DocumentsUI folder must expose the selected item's menu, not its hidden directory menu. */
class MobileSelectionMenuDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val fixture = MobileInteractionFixture()

    @Test fun selectedFileExposesItsVisibleContextMenu() {
        require(InstrumentationRegistry.getArguments().getString("helixSelectionMenu") == "true")
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        val report = JSONObject()
        try {
            MobileInteractionSession(app, setOf("com.google.android.documentsui")).use { session ->
                prepareFolder()
                session.output("ui.device")
                SystemClock.sleep(1000)
                val snapshot = session.output("ui.snapshot")
                report.put("before", JSONObject(snapshot.toString()))
                val nodes = snapshot.getValue("nodes").jsonArray.map { it.jsonObject }
                val label =
                    nodes.first {
                        it["text"]?.jsonPrimitive?.content?.startsWith("helix_menu_") == true && it["longClick"] != null
                    }
                val longClick = label.getValue("longClick").jsonObject
                assertEquals("ui.long_click", longClick.getValue("tool").jsonPrimitive.content)
                val token = longClick.getValue("token").jsonPrimitive.content
                val action =
                    session.output(
                        longClick.getValue("tool").jsonPrimitive.content,
                        fixture.json("""{"token":"$token"}"""),
                    )
                report.put("longClick", JSONObject(action.toString()))
                SystemClock.sleep(1000)
                report.put("rawSelected", inspect())
                val selected = session.output("ui.snapshot")
                report.put("selected", JSONObject(selected.toString()))
                assertTrue(
                    selected
                        .getValue("capturedNodeCount")
                        .jsonPrimitive.content
                        .toInt() > 200,
                )
                openContextMenu(session, report)
            }
        } finally {
            val directory = File(app.filesDir, "mobile-selection-evidence").apply { mkdirs() }
            File(directory, "menu.json").writeText(report.toString(2))
        }
    }

    private fun openContextMenu(
        session: MobileInteractionSession,
        report: JSONObject,
    ) {
        val query = """{"contentDescription":"More options","clickable":true,"match":"exact"}"""
        val found = session.output("ui.find", fixture.json(query))
        report.put("menuTarget", JSONObject(found.toString()))
        assertEquals("true", found.getValue("ambiguous").jsonPrimitive.content)
        val menu =
            found.getValue("nodes").jsonArray.map { it.jsonObject }.single { node ->
                node["ancestorContexts"]?.jsonArray?.any { context ->
                    context.jsonObject
                        .getValue("labels")
                        .jsonArray
                        .any { it.jsonPrimitive.content == "1 selected" }
                } == true
            }
        val token = menu.getValue("token").jsonPrimitive.content
        val action = session.output("ui.click", fixture.json("""{"token":"$token"}"""))
        report.put("menuClick", JSONObject(action.toString()))
        val expected = """{"condition":"present","text":"Move to","match":"contains","timeoutMillis":3000}"""
        val wait = session.output("ui.wait", fixture.json(expected))
        report.put("menu", JSONObject(session.output("ui.snapshot").toString()))
        report.put("wait", JSONObject(wait.toString()))
        assertEquals("FOUND", wait.getValue("status").jsonPrimitive.content)
        assertTrue(
            report
                .getJSONObject("menu")
                .getJSONArray("nodes")
                .toString()
                .contains("Move to"),
        )
    }

    private fun prepareFolder() {
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                ).setPackage("com.google.android.documentsui")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        SystemClock.sleep(1000)
        LongPressReferenceInput.openFixtureDirectory()
        SystemClock.sleep(1000)
        scrollFolderOnce()
        SystemClock.sleep(1000)
    }

    private fun scrollFolderOnce() {
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val root = checkNotNull(automation.rootInActiveWindow)
        try {
            val lists = root.findAccessibilityNodeInfosByViewId("com.google.android.documentsui:id/dir_list")
            try {
                check(lists.single().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            } finally {
                lists.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    private fun inspect(): JSONArray {
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val output = JSONArray()

        fun visit(
            node: AccessibilityNodeInfo,
            path: List<Int>,
        ) {
            try {
                check(path.size <= 64 && output.length() < 4000)
                val bounds = Rect().also(node::getBoundsInScreen)
                output.put(
                    JSONObject()
                        .put("path", JSONArray(path))
                        .put("text", node.text?.toString())
                        .put("description", node.contentDescription?.toString())
                        .put("viewId", node.viewIdResourceName)
                        .put("visible", node.isVisibleToUser)
                        .put("bounds", bounds.toShortString())
                        .put("actions", JSONArray(node.actionList.map { it.id })),
                )
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, path + index) }
            } finally {
                node.recycle()
            }
        }
        visit(checkNotNull(automation.rootInActiveWindow), emptyList())
        return output
    }
}
