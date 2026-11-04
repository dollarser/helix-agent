package com.helix.app.eval;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.InputStream;
import java.security.MessageDigest;

/** A separate-UID recipient that reads the entire temporary FileProvider grant. */
public final class FileShareReceiptActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent(getIntent().getStringExtra("receiptAction"));
        result.setPackage(getIntent().getStringExtra("receiptPackage"));
        try {
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null || !"content".equals(uri.getScheme())) throw new IllegalArgumentException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                if (stream == null) throw new IllegalStateException();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    total += count;
                    if (total > 2 * 1024 * 1024) throw new IllegalStateException();
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            result.putExtra("sha256", hash.toString());
            result.putExtra("bytes", total);
            result.putExtra("uid", android.os.Process.myUid());
        } catch (Exception error) {
            result.putExtra("error", error.getClass().getSimpleName());
        }
        sendBroadcast(result);
        finish();
    }
}
