package com.helix.app.eval;

import android.app.Activity;
import android.content.pm.PackageManager;

/** Test APK runs independently; platform callbacks must not require the target APK's Kotlin runtime. */
public class NotificationFixtureActivity extends Activity {
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 104 && results.length > 0) {
            getSharedPreferences("capability-fixture", MODE_PRIVATE).edit()
                .putInt("notification", results[0] == PackageManager.PERMISSION_GRANTED ? 1 : 0).commit();
        }
    }
}
