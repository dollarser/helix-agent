package com.helix.app.eval;

import android.app.Activity;
import android.os.Build;
import android.view.MotionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The independent test APK process has no host Kotlin runtime. Keep its touch oracle in Java. */
public abstract class BackgroundTouchActivity extends Activity {
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int displayId = -1;
            if (Build.VERSION.SDK_INT >= 30 && getDisplay() != null) {
                displayId = getDisplay().getDisplayId();
            }
            String fact = "display=" + displayId + ";down=true;uptime=" + event.getEventTime();
            try (FileOutputStream output = new FileOutputStream(new File(getFilesDir(), "background-touch.txt"))) {
                output.write(fact.getBytes(StandardCharsets.UTF_8));
            } catch (IOException failure) {
                throw new IllegalStateException("Synthetic DOWN evidence could not be recorded", failure);
            }
        }
        return super.dispatchTouchEvent(event);
    }
}
