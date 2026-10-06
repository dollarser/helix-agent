package com.helix.app.eval;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.content.SharedPreferences;

/** Owned oracle records framework filtering before the app click listener. No production endpoint. */
@android.annotation.TargetApi(34)
public final class TouchReviewActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("touch-review", MODE_PRIVATE);
        prefs.edit().clear().commit();
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(60, 400, 60, 0);
        String mode = getIntent().getStringExtra("mode");
        View button = "no-semantics".equals(mode) ? new View(this) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                canvas.drawColor(android.graphics.Color.LTGRAY);
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(android.graphics.Color.BLACK);
                paint.setTextSize(40);
                canvas.drawText("TOUCH REVIEW", 30, 100, paint);
            }
            @Override public boolean onFilterTouchEventForSecurity(MotionEvent event) {
                boolean accepted = super.onFilterTouchEventForSecurity(event);
                prefs.edit().putString("event", "flags=" + event.getFlags()
                    + ";accepted=" + accepted).commit();
                return accepted;
            }
        } : new Button(this) {
            @Override public boolean onFilterTouchEventForSecurity(MotionEvent event) {
                boolean accepted = super.onFilterTouchEventForSecurity(event);
                prefs.edit().putString("event", "action=" + event.getActionMasked()
                    + ";flags=" + event.getFlags() + ";accepted=" + accepted
                    + ";sensitive=" + isAccessibilityDataSensitive()).commit();
                return accepted;
            }
        };

        if ("sensitive".equals(mode)) button.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES);
        if ("obscured".equals(mode)) button.setFilterTouchesWhenObscured(true);
        if (button instanceof Button) ((Button) button).setText("TOUCH REVIEW");
        button.setOnClickListener(v -> prefs.edit().putInt("clicks", prefs.getInt("clicks", 0) + 1).commit());
        layout.addView(button, new LinearLayout.LayoutParams(800, 180));
        setContentView(layout);
        button.post(() -> {
            int[] xy = new int[2];
            button.getLocationOnScreen(xy);
            prefs.edit().putInt("x", xy[0] + button.getWidth()/2)
                .putInt("y", xy[1] + button.getHeight()/2).commit();
        });
    }
}
