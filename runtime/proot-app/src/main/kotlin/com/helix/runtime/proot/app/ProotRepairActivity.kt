package com.helix.runtime.proot.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.helix.runtime.proot.core.InstallOutcome
import com.helix.runtime.proot.core.RootFsInstaller
import com.helix.runtime.proot.core.RuntimeLockCodec

/**
 * The minimal user-gated settings/repair entry (ADR-0007 decision 1, section 6.7):
 * the ONLY way into the companion. It is exported behind the set's signature
 * permission and has NO launcher icon — the platform (or an OEM) force-stopped /
 * disabled state is recovered only when the user, from the main app, explicitly
 * clicks "修复 Runtime", which starts this activity via an explicit intent.
 *
 * Deliberately plain views (no UI framework dependency): one status block, one
 * install/repair action, one close action. The install runs the HXA-082 installer
 * on a worker thread; the activity is a progress surface, not a second app.
 */
class ProotRepairActivity : Activity() {
    // The installer's ELF pre-check page size. 083 has no native getpagesize seam
    // (HXA-084 owns it); 4 KiB is the conservative baseline and the shipped assets
    // are build-gated at 16 KiB alignment, so this cannot admit a 16 KiB-incompatible ELF.
    private companion object {
        const val INSTALL_PAGE_SIZE_BYTES = 4096L
    }

    private lateinit var statusView: TextView
    private lateinit var installButton: Button
    private var busy = false

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
                setText(R.string.proot_repair_install)
                setOnClickListener {
                    runInstall()
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
        root.addView(closeButton)
        setContentView(root)
        refreshState()
    }

    private fun refreshState() {
        val lines = mutableListOf<String>()
        val lock =
            runCatching { ProotRuntimeInstaller.loadEmbeddedLock(this) }
                .fold(
                    onSuccess = { "内嵌 lock：基线通过（${it.abi.wire}，指纹 ${RuntimeLockCodec.sha256Hex(it).take(12)}…）" },
                    onFailure = { "内嵌 lock：基线校验失败（${it.message?.take(80)}）" },
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
            lines += "安装状态：未安装"
        } else {
            lines += "安装状态：活动版本 ${active.installId}"
            if (rollback != null) {
                lines += "回滚版本：${rollback.installId}"
            }
        }
        statusView.text = lines.joinToString("\n")
    }

    private fun runInstall() {
        if (busy) return
        busy = true
        installButton.isEnabled = false
        statusView.append("\n正在安装 / 修复（解压约 131 MiB，需要数分钟）…")
        Thread {
            val outcome =
                runCatching {
                    val lock = ProotRuntimeInstaller.loadEmbeddedLock(this)
                    val request =
                        ProotRuntimeInstaller.buildInstallRequest(
                            this,
                            lock,
                            INSTALL_PAGE_SIZE_BYTES,
                            System.currentTimeMillis(),
                        )
                    RootFsInstaller.install(request)
                }
            runOnUiThread {
                busy = false
                installButton.isEnabled = true
                statusView.append("\n${formatOutcome(outcome)}")
                refreshState()
            }
        }.start()
    }

    private fun formatOutcome(outcome: Result<InstallOutcome>): String =
        when {
            outcome.isFailure -> {
                "安装失败：${outcome.exceptionOrNull()?.message ?: "未知错误"}"
            }

            else -> {
                val result = outcome.getOrThrow()
                if (result is InstallOutcome.Success) {
                    "安装成功：${result.installId}（${result.members} 成员，${result.extractedBytes} 字节）"
                } else {
                    val failure = result as InstallOutcome.Failure
                    "安装失败（${failure.stage}）：${failure.reason}"
                }
            }
        }
}
