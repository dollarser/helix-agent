package com.helix.app.eval

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.io.File

/** Owned test APK source. The harness stages only this fixture; Mobile Use handles system confirmation. */
class AutomationInstallSourceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val apk = File(cacheDir, "install-fixture.apk")
        check(apk.isFile) { "Stage the owned install fixture before running" }
        val uri = Uri.parse("content://$packageName.installfixture/fixture.apk")
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        finish()
    }
}
