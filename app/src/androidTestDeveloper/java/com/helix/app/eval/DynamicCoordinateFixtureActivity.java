package com.helix.app.eval;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** No accounts or network. A canvas-only target, changing comments, and a same-window fake payment layer. */
public final class DynamicCoordinateFixtureActivity extends Activity {
    private static DynamicCoordinateFixtureActivity active;
    private static volatile int clicks, ticks, x, y, pressMillis, doubleTaps, downGapMillis, launchId;
    private static volatile String mode = "normal";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private View surface;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        active = this;
        launchId = getIntent().getIntExtra("launchId", 0);
        x = 0;
        y = 0;
        clicks = 0;
        doubleTaps = 0;
        downGapMillis = 0;
        ticks = 0;
        mode = "normal";
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.WHITE);
        surface = new View(this) {
            private final Paint paint = new Paint();
            private long down;
            private long previousDown;
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(mode.equals("modal") ? Color.RED : Color.rgb((ticks * 17) % 256, (ticks * 31) % 256, (ticks * 47) % 256));
                paint.setColor(Color.WHITE);
                paint.setTextSize(40);
                canvas.drawText(mode.equals("modal") ? "PAY 1 (FAKE)" : "ANIMATED VIDEO", 40, 80, paint);
                if (mode.equals("overlay")) {
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(getWidth() / 2 - 90, 100, getWidth() / 2 + 90, 200, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawText("LIKE", getWidth() / 2 - 50, 140, paint);
                }
                int[] location = new int[2];
                getLocationOnScreen(location);
                x = location[0] + getWidth() / 2;
                y = location[1] + getHeight() / 2;
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    down = event.getEventTime();
                    downGapMillis = (int) (down - previousDown);
                    if (previousDown > 0 && downGapMillis <= android.view.ViewConfiguration.getDoubleTapTimeout()) doubleTaps++;
                    previousDown = down;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    clicks++;
                    pressMillis = (int) (event.getEventTime() - down);
                }
                return true;
            }
        };
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layout.addView(surface, new LinearLayout.LayoutParams(-1, 300));
        TextView comments = new TextView(this);
        comments.setTextSize(24);
        layout.addView(comments, new LinearLayout.LayoutParams(-1, 300));
        setContentView(layout);
        handler.post(new Runnable() {
            @Override public void run() {
                comments.setText("Comment " + (++ticks));
                comments.setBackgroundColor(ticks % 2 == 0 ? Color.LTGRAY : Color.YELLOW);
                surface.invalidate();
                handler.postDelayed(this, 40);
            }
        });
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (active == this) active = null;
        super.onDestroy();
    }

    public static final class StateProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(new String[] {"clicks", "ticks", "x", "y", "mode", "pressMillis", "doubleTaps", "downGapMillis", "launchId"});
            cursor.addRow(new Object[] {clicks, ticks, x, y, mode, pressMillis, doubleTaps, downGapMillis, launchId});
            return cursor;
        }
        @Override public Bundle call(String method, String argument, Bundle extras) {
            if (!method.equals("mode") || !("normal".equals(argument) || "modal".equals(argument) || "overlay".equals(argument))) throw new IllegalArgumentException();
            new Handler(Looper.getMainLooper()).post(() -> {
                mode = argument;
                if (active != null) active.surface.invalidate();
            });
            return Bundle.EMPTY;
        }
        @Override public String getType(Uri uri) { return "vnd.android.cursor.item/fixture"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
