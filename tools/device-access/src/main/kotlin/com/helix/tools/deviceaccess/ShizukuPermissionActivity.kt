package com.helix.tools.deviceaccess

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import rikka.shizuku.Shizuku

/** Non-exported, reached only by the user's authorization button. No model tool launches this. */
class ShizukuPermissionActivity : Activity() {
    private val listener =
        Shizuku.OnRequestPermissionResultListener { code, _ ->
            if (code == REQUEST) finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                finish()
            } else if (savedInstanceState == null) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(::startActivity)
                    finish()
                } else {
                    Shizuku.requestPermission(REQUEST)
                }
            }
        } catch (_: RuntimeException) {
            finish()
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(listener)
        super.onDestroy()
    }

    private companion object {
        const val REQUEST = 4812
    }
}
