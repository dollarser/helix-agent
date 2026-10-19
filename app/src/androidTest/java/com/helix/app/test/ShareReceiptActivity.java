package com.helix.app.test;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test APK only, explicit component only; receives synthetic fixtures on another UID. */
public final class ShareReceiptActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent request = getIntent();
        Intent receipt = new Intent(request.getStringExtra("receiptAction"));
        receipt.setPackage(request.getStringExtra("receiptPackage"));
        Uri uri = (Uri) request.getParcelableExtra(Intent.EXTRA_STREAM);
        receipt.putExtra("receiverUid", Process.myUid());
        receipt.putExtra("mime", request.getType());
        receipt.putExtra("flags", request.getFlags());
        if (uri != null) {
            receipt.putExtra("resolverMime", getContentResolver().getType(uri));
            receipt.putExtra("readPermission", checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION));
            receipt.putExtra("writePermission", checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while (body.size() < 65536 && (count = stream.read(buffer, 0, Math.min(buffer.length, 65536 - body.size()))) != -1) body.write(buffer, 0, count);
                receipt.putExtra("body", new String(body.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception failure) {
                receipt.putExtra("failure", failure.getClass().getSimpleName());
            }
        } else {
            receipt.putExtra("body", request.getStringExtra(Intent.EXTRA_TEXT));
        }
        sendBroadcast(receipt);
        finish();
    }
}
