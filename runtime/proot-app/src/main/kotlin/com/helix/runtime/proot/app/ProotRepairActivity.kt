package com.helix.runtime.proot.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RollbackOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeInstallQueries
import com.helix.runtime.proot.core.RuntimeLockCodec
import com.helix.runtime.proot.core.RuntimeUpdateState
import com.helix.runtime.proot.core.updateStateFor
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import java.io.File

/**
 * The minimal user-gated settings/repair entry (ADR-0007 decision 1, section 6.7):
 * the ONLY way into the companion. It is exported behind the set's signature
 * permission and has NO launcher icon — the platform (or an OEM) force-stopped /
 * disabled state is recovered only when the user, from the main app, explicitly
 * clicks "修复 Runtime", which starts this activity via an explicit intent.
 *
 * HXA-087 更新/回滚/完整删除: the primary button now distinguishes INSTALL (no
 * active install), UPDATE (the APK's embedded lock is newer than the active
 * install's lock — 同签名 APK 更新; the install path is the SAME atomic HXA-082
 * one, and a failed update leaves the old runtime usable) and REPAIR (same
 * version; reinstall). A rollback button appears when a kept rollback version
 * exists (`RootFsInstaller.activateRollback` — no files move). When the main app
 * opens the activity with the [ProotRuntimeProtocol.EXTRA_REMOVE_RUNTIME] extra
 * (the user clicked "删除 Runtime" there), a second explicit in-surface remove
 * button appears: the click is the consent carried across the uid boundary
 * AND the in-surface confirmation. Removal deletes ONLY `filesDir/runtime`
 * (never the Workspace — see [ProotRuntimeRemoval]).
 *
 * Deliberately plain views (no UI framework dependency): one status block, the
 * action buttons, one close action. Long operations run on a worker thread; the
 * activity is a progress surface, not a second app.
 */
class ProotRepairActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var installButton: Button
    private lateinit var rollbackButton: Button
    private lateinit var removeButton: Button
    private var busy = false

    /** The main app's user consent for a complete removal (HXA-087 完整删除). */
    private val removeRequested: Boolean by lazy {
        intent?.getBooleanExtra(ProotRuntimeProtocol.EXTRA_REMOVE_RUNTIME, false) ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        val title =
            TextView(this).apply {
                setText(R.string.proot_repair_title)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, 24)
            }
        statusView =
            TextView(this).apply {
                textSize = 14f
                setPadding(0, 0, 0, 24)
            }
        installButton =
            Button(this).apply {
                setOnClickListener {
                    if (removeRequested) runRemove() else runInstall()
                }
            }
        rollbackButton =
            Button(this).apply {
                setText(R.string.proot_repair_rollback)
                visibility = View.GONE
                setOnClickListener {
                    runRollback()
                }
            }
        removeButton =
            Button(this).apply {
                setText(R.string.proot_repair_remove)
                visibility = if (removeRequested) View.VISIBLE else View.GONE
                setOnClickListener {
                    runRemove()
                }
            }
        val closeButton =
            Button(this).apply {
                setText(R.string.proot_repair_done)
                setOnClickListener { finish() }
            }
        root.addView(title)
        root.addView(statusView)
        root.addView(installButton)
        root.addView(rollbackButton)
        root.addView(removeButton)
        root.addView(closeButton)
        setContentView(root)
        refreshState()
    }

    /** The update state against the embedded lock; null = the embedded lock is unusable. */
    private fun currentUpdateState(): Pair<RuntimeUpdateState, String>? {
        val embedded =
            runCatching { ProotRuntimeInstaller.loadEmbeddedLock(this) }.getOrNull() ?: return null
        val embeddedSha = RuntimeLockCodec.sha256Hex(embedded)
        val activeSha =
            runCatching { RuntimeInstallQueries.activeLockSha256(ProotRuntimeInstaller.runtimeRoot(this)) }
                .getOrNull()
        return updateStateFor(embeddedSha, activeSha) to embeddedSha
    }

    private fun refreshState() {
        val lines = mutableListOf<String>()
        val lock =
            runCatching { ProotRuntimeInstaller.loadEmbeddedLock(this) }
                .fold(
                    onSuccess = {
                        getString(
                            R.string.proot_embedded_lock_valid,
                            it.abi.wire,
                            RuntimeLockCodec.sha256Hex(it).take(12),
                        )
                    },
                    onFailure = { getString(R.string.proot_embedded_lock_invalid, it.message?.take(80)) },
                )
        lines += lock
        val runtimeRoot = ProotRuntimeInstaller.runtimeRoot(this)
        val active =
            runCatching { RootFsInstaller.currentActive(runtimeRoot) }
                .getOrNull()
        val rollback =
            runCatching { RootFsInstaller.currentRollback(runtimeRoot) }
                .getOrNull()
        if (active == null) {
            lines += getString(R.string.proot_install_state_none)
        } else {
            lines += getString(R.string.proot_install_state_active, active.installId)
            if (rollback != null) {
                lines += getString(R.string.proot_install_state_rollback, rollback.installId)
            }
        }
        when (currentUpdateState()?.first) {
            null -> Unit
            RuntimeUpdateState.INSTALL -> lines += getString(R.string.proot_embedded_state_install)
            RuntimeUpdateState.UPDATE -> lines += getString(R.string.proot_embedded_state_update)
            RuntimeUpdateState.REPAIR -> lines += getString(R.string.proot_embedded_state_match)
        }
        statusView.text = lines.joinToString("\n")
        installButton.isEnabled = !busy
        installButton.setText(
            when (currentUpdateState()?.first) {
                RuntimeUpdateState.UPDATE -> R.string.proot_repair_update
                else -> R.string.proot_repair_install
            },
        )
        rollbackButton.visibility = if (rollback != null && active != null) View.VISIBLE else View.GONE
        rollbackButton.isEnabled = !busy
    }

    private fun runInstall() {
        if (busy) return
        busy = true
        installButton.isEnabled = false
        val verb =
            when (currentUpdateState()?.first) {
                RuntimeUpdateState.UPDATE -> getString(R.string.proot_action_update)
                else -> getString(R.string.proot_action_install_repair)
            }
        statusView.append("\n" + getString(R.string.proot_install_progress, verb))
        Thread {
            val outcome =
                runCatching {
                    val lock = ProotRuntimeInstaller.loadEmbeddedLock(this)
                    val request =
                        ProotRuntimeInstaller.buildInstallRequest(
                            this,
                            lock,
                            ProotNative.pageSizeBytes(),
                            System.currentTimeMillis(),
                        )
                    RootFsInstaller.install(request)
                }
            runOnUiThread {
                busy = false
                statusView.append("\n${formatOutcome(outcome)}")
                refreshState()
            }
        }.start()
    }

    /**
     * The user-click rollback (HXA-087): swaps active and the kept rollback
     * atomically (no files move, HXA-082 `activateRollback`). After a rollback the
     * active install's lock may differ from the main app's verified anchor — the
     * next user-click "验证 Runtime" surfaces that as 需更新 and the user
     * re-baselines explicitly; nothing is accepted silently.
     */
    private fun runRollback() {
        if (busy) return
        busy = true
        rollbackButton.isEnabled = false
        installButton.isEnabled = false
        statusView.append("\n" + getString(R.string.proot_rollback_progress))
        Thread {
            val outcome: RollbackOutcome =
                runCatching {
                    RootFsInstaller.activateRollback(
                        ProotRuntimeInstaller.runtimeRoot(this),
                        System.currentTimeMillis(),
                    )
                }.fold(
                    onSuccess = { it },
                    onFailure = { RollbackOutcome.Failed(it.message ?: getString(R.string.proot_unknown_error)) },
                )
            runOnUiThread {
                busy = false
                statusView.append("\n${formatRollback(outcome)}")
                refreshState()
            }
        }.start()
    }

    private fun formatRollback(outcome: RollbackOutcome): String =
        when (outcome) {
            is RollbackOutcome.Done -> {
                getString(R.string.proot_rollback_success, outcome.newActiveId, outcome.newRollbackId)
            }

            RollbackOutcome.NoActive -> {
                getString(R.string.proot_rollback_no_active)
            }

            RollbackOutcome.NoRollback -> {
                getString(R.string.proot_rollback_no_saved)
            }

            is RollbackOutcome.Failed -> {
                getString(R.string.proot_rollback_failure, outcome.reason)
            }
        }

    /**
     * The user-click complete removal (HXA-087 完整删除): deletes ONLY the
     * `filesDir/runtime` state tree (all version dirs + state pointers; job
     * journals/evidence live under it). The main app's Workspace is in another
     * package and is never touched — see [ProotRuntimeRemoval] for the scoping.
     */
    private fun runRemove() {
        if (busy) return
        busy = true
        removeButton.isEnabled = false
        installButton.isEnabled = false
        rollbackButton.isEnabled = false
        statusView.append("\n" + getString(R.string.proot_remove_progress))
        Thread {
            val result =
                runCatching { ProotRuntimeRemoval.remove(File(filesDir, "runtime")) }
                    .fold(
                        onSuccess = { it },
                        onFailure = { ProotRuntimeRemoval.Result(false, false, null) },
                    )
            runOnUiThread {
                busy = false
                if (result.removed) {
                    statusView.append(
                        "\n" +
                            getString(
                                R.string.proot_remove_success,
                                result.activeInstallId ?: getString(R.string.proot_none),
                            ),
                    )
                } else {
                    statusView.append("\n" + getString(R.string.proot_remove_failure))
                }
                removeButton.visibility = View.GONE
                refreshState()
            }
        }.start()
    }

    private fun formatOutcome(outcome: Result<InstallOutcome>): String =
        when {
            outcome.isFailure -> {
                getString(
                    R.string.proot_install_failure,
                    outcome.exceptionOrNull()?.message ?: getString(R.string.proot_unknown_error),
                )
            }

            else -> {
                val result = outcome.getOrThrow()
                if (result is InstallOutcome.Success) {
                    getString(
                        R.string.proot_install_success,
                        result.installId,
                        result.members,
                        result.extractedBytes,
                    )
                } else {
                    val failure = result as InstallOutcome.Failure
                    getString(R.string.proot_install_stage_failure, failure.stage, failure.reason)
                }
            }
        }
}
