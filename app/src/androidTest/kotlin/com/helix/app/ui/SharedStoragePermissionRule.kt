package com.helix.app.ui

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.PermissionChecker
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Explicit manual-file fixture grant; never changes the Agent's scope registry. */
class SharedStoragePermissionRule : ExternalResource() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val packageName get() = instrumentation.targetContext.packageName
    private val restoreModes = mutableMapOf<String, String>()

    override fun before() {
        if (Build.VERSION.SDK_INT < 30) {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE).forEach {
                val op = it.substringAfterLast('.')
                val allowed =
                    PermissionChecker.checkSelfPermission(instrumentation.targetContext, it) ==
                        PermissionChecker.PERMISSION_GRANTED
                restoreModes[op] = if (allowed) "allow" else "ignore"
                instrumentation.uiAutomation.grantRuntimePermission(packageName, it)
                shell("appops set $packageName $op allow")
            }
        } else {
            check(InstrumentationRegistry.getArguments().getString("hxaStoragePhase") == "granted") {
                "Use the mandatory host storage phases: changing MANAGE_EXTERNAL_STORAGE can kill instrumentation"
            }
            check(
                android.os.Environment.isExternalStorageManager(),
            ) { "Host must grant storage before instrumentation" }
        }
    }

    override fun after() {
        // Revoking runtime permission kills instrumentation. Restore effective access via
        // AppOps; the owned runner removes this test installation at teardown.
        restoreModes.forEach { (op, mode) -> shell("appops set $packageName $op $mode") }
    }

    fun revokeAccess() {
        val op = if (Build.VERSION.SDK_INT >= 30) "MANAGE_EXTERNAL_STORAGE" else "READ_EXTERNAL_STORAGE"
        shell("appops set $packageName $op ignore")
    }

    fun withGrant(block: () -> Unit) {
        before()
        try {
            block()
        } finally {
            after()
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
