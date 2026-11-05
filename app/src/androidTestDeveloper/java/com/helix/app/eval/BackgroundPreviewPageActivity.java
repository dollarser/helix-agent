package com.helix.app.eval;

import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A second owned window for local preview transition checks; no network, accounts or user files. */
public final class BackgroundPreviewPageActivity extends BackgroundTouchActivity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.MAGENTA);
        page.setPadding(20, 80, 20, 20);
        TextView title = new TextView(this);
        title.setText("BACKGROUND_PREVIEW_PAGE");
        page.addView(title);
        Button back = new Button(this);
        back.setText("Back to fixture");
        back.setAllCaps(false);
        back.setOnClickListener(view -> finish());
        page.addView(back);
        setContentView(page);
    }
}
