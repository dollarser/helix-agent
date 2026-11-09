package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.extensions.mobileuse.automation.AutomationPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal class MobileInteractionFixture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val packageName = instrumentation.context.packageName
    private val uri = Uri.parse("content://$packageName.mobile-interaction")

    fun launch() {
        val id = (SystemClock.elapsedRealtime() % Int.MAX_VALUE).toInt()
        context.startActivity(
            Intent()
                .setComponent(ComponentName(packageName, MobileInteractionFixtureActivity::class.java.name))
                .putExtra("launchId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        await("fixture first draw and scheduled content") {
            number("launchId").toInt() == id && number("cx") > 0 && number("ticks") > 0 && number("bx") > 0
        }
    }

    fun number(name: String): Double = read(name).toDouble()

    fun read(name: String): String =
        requireNotNull(context.contentResolver.query(uri, null, null, null, null)).use {
            check(it.moveToFirst())
            it.getString(it.getColumnIndexOrThrow(name))
        }

    fun change(name: String) {
        context.contentResolver.call(uri, name, null, null)
    }

    fun center() = AutomationPoint(number("cx").toFloat(), number("cy").toFloat())

    fun point(
        x: String,
        y: String,
    ) = "{\"x\":${number(x)},\"y\":${number(y)}}"

    fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    fun frame(token: String) = JsonObject(mapOf("frame" to JsonPrimitive(token)))

    fun await(
        label: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        error("Timed out waiting for $label")
    }
}
