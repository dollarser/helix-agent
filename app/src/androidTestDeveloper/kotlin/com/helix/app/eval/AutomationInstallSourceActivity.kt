package com.helix.app.eval

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import java.io.File

/** Owned test APK source. The harness stages only this fixture; Mobile Use handles system confirmation. */
class AutomationInstallSourceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apk = File(cacheDir, "install-fixture.apk")
        check(apk.isFile) { "Stage the owned install fixture before running" }
        setContentView(
            Button(this).apply {
                text = "安装测试应用 Helix Install Fixture"
                setOnClickListener { openInstaller() }
            },
        )
        // Keep a real source page in the task. Returning from source authorization must not
        // strand the model on the launcher; retry still requires an explicit button click.
        if (savedInstanceState == null) openInstaller()
    }

    private fun openInstaller() {
        val uri = Uri.parse("content://$packageName.installfixture/fixture.apk")
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
