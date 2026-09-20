package com.helix.spike.termlib;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Gives the clipboard test an actual focused app window; not terminal UI acceptance. */
public final class RendererProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("Terminal parser device probe");
        setContentView(view);
    }
}
