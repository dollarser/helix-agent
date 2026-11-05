package com.helix.app.eval;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;

/** Standalone test APK activity: no host Kotlin runtime, accounts, network or user files. */
public final class BackgroundDisplayFixtureActivity extends BackgroundTouchActivity {
    private int count;
    private int visualCount;
    private SharedPreferences facts;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        count = saved == null ? 0 : saved.getInt("count");
        visualCount = saved == null ? 0 : saved.getInt("visualCount");
        if (saved == null) new File(getFilesDir(), "background-touch.txt").delete();
        facts = getSharedPreferences("background-fixture", 0);
        facts.edit().putInt("display", displayId()).putInt("task", getTaskId()).commit();
        if (getIntent().hasExtra("numericReadback")) {
            facts.edit().putBoolean("numericReadback", getIntent().getBooleanExtra("numericReadback", false)).commit();
        }
        if (facts.getBoolean("numericReadback", false)) {
            LinearLayout numbers = new LinearLayout(this);
            numbers.setOrientation(LinearLayout.VERTICAL);
            numbers.setPadding(24, 120, 24, 24);
            for (String line : new String[] {"公开数据校验", "下载：12.3万次", "评论：47条", "评分：4.8分", "更新时间：2026-11-04"}) {
                TextView value = label(line);
                value.setTextSize(24f);
                numbers.addView(value);
            }
            setContentView(numbers);
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(20, 80, 20, 20);
        content.addView(label("BACKGROUND_DISPLAY:" + displayId()));
        content.addView(label("BACKGROUND_TASK_ID:" + getTaskId()));
        TextView result = label("BACKGROUND_COUNT:" + count);
        content.addView(result);
        TextView visual = label("VISUAL_COUNT:" + visualCount);
        content.addView(visual);
        content.addView(new BackgroundVisualFixtureView(this, () -> {
            visual.setText("VISUAL_COUNT:" + (++visualCount));
            facts.edit().putInt("visual", visualCount).commit();
        }), new LinearLayout.LayoutParams(-1, 330));
        EditText input = new EditText(this);
        input.setId(android.R.id.edit);
        input.setHint("Background text");
        input.setShowSoftInputOnFocus(false);
        content.addView(input);
        Button increment = new Button(this);
        increment.setId(android.R.id.button1);
        increment.setText("Increment");
        increment.setAllCaps(false);
        increment.setOnClickListener(view -> {
            result.setText("BACKGROUND_COUNT:" + (++count));
            facts.edit().putInt("clicks", count).commit();
        });
        content.addView(increment);
        Button page = new Button(this);
        page.setId(android.R.id.button2);
        page.setText("Open page");
        page.setAllCaps(false);
        page.setOnClickListener(view -> startActivity(new android.content.Intent(this, BackgroundPreviewPageActivity.class)));
        content.addView(page);
        content.addView(scroller(), new LinearLayout.LayoutParams(-1, 220));
        setContentView(content);
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        return view;
    }

    private ScrollView scroller() {
        ScrollView scroll = new ScrollView(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                boolean accepted = super.performAccessibilityAction(action, arguments);
                facts.edit().putInt("scrollAction", action).putBoolean("scrollAccepted", accepted).apply();
                return accepted;
            }
        };
        scroll.setId(android.R.id.list);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int row = 0; row < 20; row++) {
            TextView item = label("SCROLL_ROW:" + row);
            item.setTextSize(18f);
            item.setPadding(8, 12, 8, 12);
            rows.addView(item);
        }
        scroll.addView(rows);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) ->
                facts.edit().putInt("scrollY", y).apply());
        return scroll;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("count", count);
        state.putInt("visualCount", visualCount);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        facts.edit().putInt("display", displayId()).commit();
    }

    private int displayId() {
        return Build.VERSION.SDK_INT >= 30 && getDisplay() != null ? getDisplay().getDisplayId() : 0;
    }
}
