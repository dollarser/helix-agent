package com.helix.app.test;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Runs in the test APK UID, emulating another app sharing only synthetic data. */
public class KnowledgeSenderActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String target = getIntent().getStringExtra("target");
        Uri uri = getIntent().getData();
        if (getIntent().getBooleanExtra("revokeOnly", false)) {
            revokeUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if (getIntent().getBooleanExtra("grantOnly", false)) {
            grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain");
            send.setClassName(target, "com.helix.app.knowledge.KnowledgeCaptureActivity");
            send.putExtra(Intent.EXTRA_TEXT, getIntent().getStringExtra("text"));
            if (uri != null) {
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newRawUri("Synthetic fixture", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            startActivity(send);
        }
        finish();
    }
}
