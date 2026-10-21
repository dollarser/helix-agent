package com.helix.runtime.proot.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.helix.runtime.proot.core.InstallFacts
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RollbackOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeInstallation
import com.helix.runtime.proot.core.RuntimeInstallationState
import com.helix.runtime.proot.core.SmokeStatus

/** User maintenance of the bundled Linux environment, entirely within the private :proot process. */
@Suppress("TooManyFunctions")
class ProotRepairActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var primaryButton: Button
    private lateinit var reinstallButton: Button
    private lateinit var rollbackButton: Button
    private lateinit var removeButton: Button
    private lateinit var detailsView: TextView
    private val actions = mutableListOf<Button>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastReceipt: String? = null
    private val refreshTask =
        object : Runnable {
            override fun run() {
                val receipt =
                    ProotMaintenancePresentation.message(this@ProotRepairActivity) +
                        ProotMaintenancePresentation.busy()
                if (lastReceipt != receipt) {
                    refreshState()
                    lastReceipt = receipt
                }
                handler.postDelayed(this, 1_000)
            }
        }

    override fun onResume() {
        super.onResume()
        lastReceipt = null
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        val title =
            TextView(this).apply {
                setText(R.string.proot_repair_title)
                textSize = 20f
            }
        statusView = TextView(this).apply { textSize = 16f }
        root.addView(title)
        root.addView(statusView)
        root.addView(TextView(this).apply { setText(R.string.proot_environment_description) })
        primaryButton = button(root, R.string.proot_repair_install) { primaryAction() }
        val maintenance =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
        button(root, R.string.proot_environment_more) {
            maintenance.visibility = if (maintenance.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        root.addView(maintenance)
        reinstallButton = button(maintenance, R.string.proot_environment_reinstall) { confirmInstall() }
        button(maintenance, R.string.proot_environment_reset) {
            confirm(R.string.proot_environment_reset, R.string.proot_remove_confirm_body) { runReset() }
        }
        rollbackButton =
            button(maintenance, R.string.proot_repair_rollback) {
                confirm(R.string.proot_repair_rollback, R.string.proot_rollback_confirm_body) { runRollback() }
            }
        removeButton =
            button(maintenance, R.string.proot_repair_remove) {
                confirm(R.string.proot_repair_remove, R.string.proot_remove_confirm_body) { runRemove() }
            }
        button(maintenance, R.string.proot_legal_title) {
            startActivity(Intent(this, ProotLegalActivity::class.java))
        }
        detailsView =
            TextView(this).apply {
                textSize = 12f
                setTextIsSelectable(true)
            }
        maintenance.addView(detailsView)
        button(root, R.string.proot_repair_done) { finish() }
        showSurface(root)
        refreshState()
    }

    private fun button(
        parent: LinearLayout,
        label: Int,
        action: () -> Unit,
    ): Button =
        Button(this).apply {
            setText(label)
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { if (!ProotMaintenancePresentation.busy()) action() }
            parent.addView(this)
            actions += this
        }

    private fun installation(): RuntimeInstallation =
        runCatching {
            RuntimeInstallation.inspect(
                ProotRuntimeInstaller.runtimeRoot(this),
                ProotRuntimeInstaller.loadEmbeddedLock(this),
            )
        }.getOrElse { RuntimeInstallation(RuntimeInstallationState.DAMAGED, diagnostic = it.message) }

    private fun refreshState() {
        val installed = installation()
        val summary = summaryLabel(installed.state)
        val receipt = ProotMaintenancePresentation.message(this)
        val feedback =
            if (receipt.isNotBlank() && !ProotMaintenancePresentation.busy()) {
                getString(R.string.proot_maintenance_previous_result, receipt)
            } else {
                receipt
            }
        statusView.text =
            listOf(getString(summary), feedback)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        primaryButton.setText(
            when (installed.state) {
                RuntimeInstallationState.NOT_PREPARED -> R.string.proot_repair_install
                RuntimeInstallationState.PREPARED -> R.string.proot_environment_check
                RuntimeInstallationState.UPDATE_REQUIRED -> R.string.proot_repair_update
                RuntimeInstallationState.DAMAGED -> R.string.proot_environment_repair
                RuntimeInstallationState.RESET_REQUIRED -> R.string.proot_environment_reset
            },
        )
        val runtimeRoot = ProotRuntimeInstaller.runtimeRoot(this)
        reinstallButton.visibility =
            if (installed.state == RuntimeInstallationState.PREPARED) View.VISIBLE else View.GONE
        rollbackButton.visibility =
            if (runCatching { RootFsInstaller.currentRollback(runtimeRoot) }.getOrNull() != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
        removeButton.visibility = if (runtimeRoot.exists()) View.VISIBLE else View.GONE
        detailsView.text =
            listOfNotNull(
                getString(R.string.proot_environment_boundary),
                installed.manifest?.let { getString(R.string.proot_install_state_active, it.installId) },
                installed.diagnostic,
                ProotMaintenancePresentation.details(this).takeIf { it.isNotBlank() },
            ).joinToString("\n\n")
        actions.forEach { it.isEnabled = !ProotMaintenancePresentation.busy() }
    }

    private fun summaryLabel(state: RuntimeInstallationState): Int =
        when (state) {
            RuntimeInstallationState.NOT_PREPARED -> R.string.proot_install_state_none
            RuntimeInstallationState.PREPARED -> R.string.proot_runtime_installed
            RuntimeInstallationState.UPDATE_REQUIRED -> R.string.proot_environment_update_required
            RuntimeInstallationState.DAMAGED -> R.string.proot_environment_damaged
            RuntimeInstallationState.RESET_REQUIRED -> R.string.proot_environment_reset_required
        }

    private fun primaryAction() {
        when (installation().state) {
            RuntimeInstallationState.NOT_PREPARED -> {
                runInstall()
            }

            RuntimeInstallationState.PREPARED -> {
                runCheck()
            }

            RuntimeInstallationState.RESET_REQUIRED -> {
                confirm(
                    R.string.proot_environment_reset,
                    R.string.proot_remove_confirm_body,
                ) { runReset() }
            }

            else -> {
                confirmInstall()
            }
        }
    }

    private fun confirmInstall() =
        confirm(R.string.proot_environment_reinstall, R.string.proot_reinstall_confirm_body) { runInstall() }

    private fun confirm(
        title: Int,
        body: Int,
        action: () -> Unit,
    ) {
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.proot_environment_confirm) { _, _ -> action() }
            .show()
    }

    private fun runInstall() = operation(R.string.proot_install_progress) { installNow() }

    private fun installNow(): String {
        check(installation().state != RuntimeInstallationState.RESET_REQUIRED) {
            getString(R.string.proot_environment_reset_required)
        }
        val lock = ProotRuntimeInstaller.loadEmbeddedLock(this)
        val request =
            ProotRuntimeInstaller.buildInstallRequest(
                this,
                lock,
                ProotNative.pageSizeBytes(),
                System.currentTimeMillis(),
            )
        return when (val result = RootFsInstaller.install(request)) {
            is InstallOutcome.Success -> getString(R.string.proot_install_success)
            is InstallOutcome.Failure -> error("${result.stage}: ${result.reason}")
        }
    }

    private fun runReset() =
        operation(R.string.proot_install_progress) {
            check(ProotRuntimeRemoval.remove(ProotRuntimeInstaller.runtimeRoot(this)).removed) {
                getString(R.string.proot_remove_failure)
            }
            installNow()
        }

    private fun runCheck() =
        operation(R.string.proot_environment_checking) {
            val result =
                RuntimeInstallation.check(
                    ProotRuntimeInstaller.runtimeRoot(this),
                    ProotRuntimeInstaller.loadEmbeddedLock(this),
                    InstallFacts(ProotNative.pageSizeBytes(), System.currentTimeMillis()),
                )
            check(result.status == SmokeStatus.PASSED) { result.failures.joinToString("; ") }
            getString(R.string.proot_environment_check_passed)
        }

    private fun runRollback() =
        operation(R.string.proot_rollback_progress) {
            when (
                val result =
                    RootFsInstaller.activateRollback(
                        ProotRuntimeInstaller.runtimeRoot(this),
                        System.currentTimeMillis(),
                    )
            ) {
                is RollbackOutcome.Done -> getString(R.string.proot_rollback_success)
                is RollbackOutcome.Failed -> error(result.reason)
                else -> error(getString(R.string.proot_rollback_no_saved))
            }
        }

    private fun runRemove() =
        operation(R.string.proot_remove_progress) {
            check(ProotRuntimeRemoval.remove(ProotRuntimeInstaller.runtimeRoot(this)).removed) {
                getString(R.string.proot_remove_failure)
            }
            getString(R.string.proot_remove_success)
        }

    private fun operation(
        progress: Int,
        action: () -> String,
    ) {
        if (!ProotMaintenancePresentation.begin(this, getString(progress))) return
        refreshState()
        Thread {
            val outcome = runCatching { ProotRuntimeMaintenance.run(this, action) }
            ProotMaintenancePresentation.finish(
                this,
                outcome.getOrElse {
                    val message =
                        if (it is ProotEnvironmentInUse) {
                            R.string.proot_environment_in_use
                        } else {
                            R.string.proot_environment_action_failed
                        }
                    getString(message)
                },
                outcome.exceptionOrNull()?.message.orEmpty(),
            )
        }.start()
    }

    private fun showSurface(root: LinearLayout) {
        val dark =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val foreground = android.graphics.Color.parseColor(if (dark) "#E6E0E9" else "#1D1B20")
        val surface = android.graphics.Color.parseColor(if (dark) "#141218" else "#FFFBFE")
        val accent = android.graphics.Color.parseColor(if (dark) "#D0BCFF" else "#6750A4")

        fun style(view: View) {
            if (view is TextView) view.setTextColor(foreground)
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) style(view.getChildAt(i))
        }
        style(root)
        actions.forEach { button ->
            button.setTextColor(accent)
            button.background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), accent)
                    setColor(surface)
                }
            button.layoutParams =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(12) }
        }
        val scroll =
            android.widget.ScrollView(this).apply {
                setBackgroundColor(surface)
                isFillViewport = true
                addView(root)
                setOnApplyWindowInsetsListener { view, insets ->
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                    insets
                }
            }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
