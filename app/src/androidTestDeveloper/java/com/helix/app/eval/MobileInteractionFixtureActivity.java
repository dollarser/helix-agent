package com.helix.app.eval;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Synthetic local-only application: real scale detector, multi-pointer motion, native controls and WebView. */
public final class MobileInteractionFixtureActivity extends Activity {
    private static MobileInteractionFixtureActivity active;
    private static volatile float scale = 1, angle, threeDy;
    private static volatile int taps, maxPointers, semanticClicks, ticks, launchId, cx, cy, bx, by, wx, wy, webY, ime, minSpan;
    private static volatile String result = "Ready";
    private static volatile int longPresses;
    private static final java.util.List<String> touchEvents = new java.util.ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private Button button;
    private EditText input;
    private TextView resultView;
    private View cover;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        active = this;
        minSpan = android.view.ViewConfiguration.get(this).getScaledMinimumScalingSpan();
        launchId = getIntent().getIntExtra("launchId", 0);
        if (state == null) {
            scale = 1; angle = 0; threeDy = 0; taps = 0; maxPointers = 0; semanticClicks = 0; ticks = 0;
            result = "Ready";
            longPresses = 0;
            synchronized (touchEvents) { touchEvents.clear(); }
        }
        cx = 0; cy = 0; webY = 0; ime = 0;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        frame = new FrameLayout(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.WHITE);
        frame.addView(content);
        View canvas = new View(this) {
            final Paint paint = new Paint();
            float previousAngle, startY;
            boolean rotating, three;
            final ScaleGestureDetector detector = new ScaleGestureDetector(MobileInteractionFixtureActivity.this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector value) {
                        scale *= value.getScaleFactor(); invalidate(); return true;
                    }
                });
            @Override protected void onDraw(Canvas drawing) {
                drawing.drawColor(Color.rgb(20, 60 + ticks % 80, 110));
                drawing.save();
                drawing.translate(getWidth() / 2f, getHeight() / 2f);
                drawing.rotate(angle); drawing.scale(scale, scale);
                paint.setColor(Color.YELLOW); drawing.drawRect(-60, -40, 60, 40, paint);
                drawing.restore();
                paint.setColor(Color.WHITE); paint.setTextSize(26);
                drawing.drawText("Scale " + scale + " Rotate " + angle, 20, 35, paint);
                int[] xy = new int[2]; getLocationOnScreen(xy);
                cx = xy[0] + getWidth() / 2; cy = xy[1] + getHeight() / 2;
            }
            @Override public boolean onTouchEvent(MotionEvent event) {
                maxPointers = Math.max(maxPointers, event.getPointerCount());
                detector.onTouchEvent(event);
                if (event.getPointerCount() == 2) {
                    float current = (float) Math.toDegrees(Math.atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0)));
                    if (rotating && event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        float delta = current - previousAngle;
                        if (delta > 180) delta -= 360;
                        if (delta < -180) delta += 360;
                        angle += delta;
                    }
                    previousAngle = current; rotating = true;
                } else rotating = false;
                if (event.getPointerCount() == 3) {
                    float mean = (event.getY(0) + event.getY(1) + event.getY(2)) / 3;
                    if (!three) startY = mean;
                    three = true;
                    threeDy = mean - startY;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { taps++; rotating = false; three = false; }
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) { rotating = false; three = false; }
                invalidate(); return true;
            }
        };
        canvas.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(canvas, new LinearLayout.LayoutParams(-1, 500));
        button = new Button(this); button.setAllCaps(false); button.setText("Apply fixture");
        button.setOnClickListener(v -> {
            semanticClicks++;
            handler.postDelayed(() -> { result = "Applied fixture"; resultView.setText(result); }, 250);
        });
        button.setOnLongClickListener(v -> { longPresses++; return true; });
        button.setOnTouchListener((v, event) -> {
            synchronized (touchEvents) {
                if (touchEvents.size() < 256) touchEvents.add(event.getActionMasked() + ":" + event.getDownTime()
                    + ":" + event.getEventTime() + ":" + android.os.SystemClock.uptimeMillis() + ":" + event.getFlags()
                    + ":" + event.getToolType(0) + ":" + event.getSource());
            }
            return false;
        });
        content.addView(button, new LinearLayout.LayoutParams(-1, 100));
        resultView = new TextView(this); resultView.setText(result); resultView.setTextSize(20);
        content.addView(resultView, new LinearLayout.LayoutParams(-1, 70));
        input = new EditText(this); input.setHint("Fixture input"); input.setSingleLine(true);
        content.addView(input, new LinearLayout.LayoutParams(-1, 100));
        TextView comments = new TextView(this); comments.setTextSize(20);
        content.addView(comments, new LinearLayout.LayoutParams(-1, 80));
        WebView web = new WebView(this) {
            @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) { super.onScrollChanged(x, y, oldX, oldY); webY = y; }
        };
        web.loadData("<html><body style='margin:0'><div style='height:1800px;background:linear-gradient(coral,skyblue)'>Local scroll fixture</div></body></html>", "text/html", "UTF-8");
        content.addView(web, new LinearLayout.LayoutParams(-1, 350));
        frame.setOnApplyWindowInsetsListener((v, insets) -> { ime = insets.isVisible(android.view.WindowInsets.Type.ime()) ? 1 : 0; return insets; });
        setContentView(frame);
        handler.post(new Runnable() {
            @Override public void run() {
                comments.setText("Comment " + (++ticks)); canvas.invalidate();
                int[] b = new int[2]; button.getLocationOnScreen(b); bx = b[0] + button.getWidth() / 2; by = b[1] + button.getHeight() / 2;
                int[] w = new int[2]; web.getLocationOnScreen(w); wx = w[0] + web.getWidth() / 2; wy = w[1] + web.getHeight() / 2;
                handler.postDelayed(this, 200);
            }
        });
    }

    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); if (active == this) active = null; super.onDestroy(); }

    public static final class StateProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor data = new MatrixCursor(new String[]{"scale","angle","threeDy","taps","maxPointers","semanticClicks","ticks","launchId","cx","cy","bx","by","wx","wy","webY","ime","result","minSpan","orientation","longPresses","touchEvents"});
            String touches;
            synchronized (touchEvents) { touches = new org.json.JSONArray(touchEvents).toString(); }
            data.addRow(new Object[]{scale,angle,threeDy,taps,maxPointers,semanticClicks,ticks,launchId,cx,cy,bx,by,wx,wy,webY,ime,result,minSpan,getContext().getResources().getConfiguration().orientation,longPresses,touches}); return data;
        }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (!java.util.Arrays.asList("keyboard","rotate","dialog","label","cover","reset-touch").contains(method)) throw new IllegalArgumentException();
            new Handler(Looper.getMainLooper()).post(() -> {
                MobileInteractionFixtureActivity a = active; if (a == null) throw new IllegalStateException("No fixture");
                switch (method) {
                    case "reset-touch": longPresses = 0; semanticClicks = 0; synchronized (touchEvents) { touchEvents.clear(); } break;
                    case "keyboard": a.input.requestFocus(); ((InputMethodManager)a.getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(a.input, InputMethodManager.SHOW_IMPLICIT); break;
                    case "rotate": a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE); break;
                    case "dialog": new AlertDialog.Builder(a).setMessage("Synthetic modal").setPositiveButton("Close", (d,w) -> d.dismiss()).show(); break;
                    case "label": a.button.setText("Different action"); break;
                    case "cover":
                        Button cover = new Button(a); cover.setAllCaps(false); cover.setText("Cover action");
                        int[] xy = new int[2]; a.button.getLocationInWindow(xy);
                        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(a.button.getWidth(), a.button.getHeight());
                        params.leftMargin = xy[0]; params.topMargin = xy[1]; a.frame.addView(cover, params); a.cover = cover; break;
                }
            }); return Bundle.EMPTY;
        }
        @Override public String getType(Uri uri) { return "vnd.android.cursor.item/helix-fixture"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
