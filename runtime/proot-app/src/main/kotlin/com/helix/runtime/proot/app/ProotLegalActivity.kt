package com.helix.runtime.proot.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.helix.runtime.proot.core.RuntimeLockCodec

/**
 * The OFFLINE legal / build-manifest page (roadmap HXA-087 法律页; architecture doc
 * section 9 + doc 10 必测: 所有第三方许可证和源码信息可离线查看).
 *
 * Same user-gated shape as [ProotRepairActivity]: exported behind the set's
 * signature permission, NO launcher icon. The ONLY caller is the main app's
 * user-click "许可证与来源" button (an explicit intent with this class name).
 *
 * Deliberately plain views (no UI framework dependency): a title, one scrollable
 * text block (offline notice + build manifest + per-component source/license +
 * full license texts), one close button. The content comes exclusively from this
 * APK's embedded assets — the companion holds no INTERNET permission, so the page
 * cannot and does not fetch anything.
 */
class ProotLegalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        val title =
            TextView(this).apply {
                setText(R.string.proot_legal_title)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, 24)
            }
        val body =
            TextView(this).apply {
                textSize = 12f
            }
        val scrollView =
            ScrollView(this).apply {
                addView(body)
                isVerticalScrollBarEnabled = true
            }
        val closeButton =
            Button(this).apply {
                setText(R.string.proot_legal_done)
                gravity = Gravity.CENTER
                setOnClickListener { finish() }
            }
        root.addView(title)
        root.addView(scrollView)
        root.addView(closeButton)
        setContentView(root)

        // Synchronous: reading a few small text assets is cheap, and a failure must
        // be VISIBLE on this page (a legal page that silently shows nothing is worse
        // than one that says it is unavailable) — never a blank screen.
        body.text =
            runCatching {
                val lock =
                    RuntimeLockCodec.parse(
                        assets.open("runtime/runtime-lock.json").bufferedReader().use { it.readText() },
                    )
                ProotLegalPage.build(this, lock)
            }.fold(
                onSuccess = { it },
                onFailure = {
                    getString(R.string.proot_legal_unavailable, it.message?.take(120))
                },
            )
    }
}
