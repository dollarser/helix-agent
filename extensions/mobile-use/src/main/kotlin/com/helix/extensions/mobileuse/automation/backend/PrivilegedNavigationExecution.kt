package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AndroidPackageName
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import java.io.File
import java.util.concurrent.TimeUnit

/** Fixed argv, bounded lifetime, private output. No model-supplied shell commands or intent extras. */
internal class PrivilegedNavigationExecution(
    private val allowed: () -> Boolean,
    private val packageManager: android.content.pm.PackageManager,
    private val displayId: Int = 0,
) {
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Once started, any failure may follow an activity launch.
    fun launch(packageName: String): AutomationActionStatus {
        require(AndroidPackageName.isValid(packageName))
        if (!allowed()) return AutomationActionStatus.ACTION_NOT_DISPATCHED
        val component = launcher(packageName) ?: return AutomationActionStatus.ACTION_NOT_SUPPORTED
        val output = File.createTempFile("helix-launch-", ".log", File("/data/local/tmp"))
        var process: Process? = null
        return try {
            if (!allowed()) return AutomationActionStatus.ACTION_NOT_DISPATCHED
            process =
                ProcessBuilder(
                    launchArguments(displayId, component.flattenToString()),
                ).redirectErrorStream(true).redirectOutput(output).start()
            if (!awaitLaunchAcknowledgement(process, allowed)) return AutomationActionStatus.ACTION_OUTCOME_UNKNOWN
            if (output.length() > 8192) return AutomationActionStatus.ACTION_OUTCOME_UNKNOWN
            val text = output.readText(Charsets.UTF_8)
            android.util.Log.i("HelixPrivileged", "Launch command exit=${process.exitValue()}")
            if (launchAcknowledged(process.exitValue(), text) && allowed()) {
                AutomationActionStatus.SUCCEEDED
            } else {
                AutomationActionStatus.ACTION_OUTCOME_UNKNOWN
            }
        } catch (error: Exception) {
            android.util.Log.w("HelixPrivileged", "Launch failed", error)
            AutomationActionStatus.ACTION_OUTCOME_UNKNOWN
        } finally {
            terminate(process)
            output.delete()
        }
    }

    private fun terminate(process: Process?) {
        if (process != null && process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "COMMAND_TERMINATION_UNCONFIRMED" }
        }
    }

    private fun launcher(packageName: String): android.content.ComponentName? =
        packageManager.getLaunchIntentForPackage(packageName)?.component?.takeIf {
            it.packageName ==
                packageName
        }
}
