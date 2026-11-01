package com.helix.app.eval;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Independent test-APK process: no Kotlin-runtime dependency or semantic target node. */
public final class BackgroundVisualFixtureView extends View {
    private final int purple = new java.util.Random().nextInt(3);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable onTarget;

    public BackgroundVisualFixtureView(Context context, Runnable onTarget) {
        super(context);
        this.onTarget = onTarget;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMinimumHeight(330);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int index = 0; index < 3; index++) {
            paint.setColor(index == purple ? Color.rgb(160, 40, 210) :
                    index == (purple + 1) % 3 ? Color.GREEN : Color.CYAN);
            canvas.drawCircle(getWidth() * (index + 0.5f) / 3, getHeight() / 2f,
                    Math.min(getWidth() / 8f, getHeight() / 3f), paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - getWidth() * (purple + 0.5f) / 3;
            float dy = event.getY() - getHeight() / 2f;
            float radius = Math.min(getWidth() / 8f, getHeight() / 3f);
            if (dx * dx + dy * dy <= radius * radius) onTarget.run();
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
