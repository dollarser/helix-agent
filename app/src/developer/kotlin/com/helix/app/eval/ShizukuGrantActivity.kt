package com.helix.app.eval

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import rikka.shizuku.Shizuku
import java.io.File

class ShizukuGrantActivity : Activity() {
    private val evidence by lazy { File(filesDir, EVIDENCE_FILE) }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != REQUEST_CODE) return@OnRequestPermissionResultListener
            evidence.writeText(
                "binder=" + Shizuku.pingBinder() +
                    ";serverUid=" + runCatching(Shizuku::getUid).getOrDefault(-1) +
                    ";granted=" + (grantResult == PackageManager.PERMISSION_GRANTED),
            )
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        if (!Shizuku.pingBinder()) {
            evidence.writeText("binder=false;granted=false")
            finish()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            evidence.writeText(
                "binder=true;serverUid=" + Shizuku.getUid() + ";granted=true",
            )
            finish()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_CODE = 9411
        const val EVIDENCE_FILE = "shizuku-grant-eval.txt"
    }
}
