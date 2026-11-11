package com.helix.app.eval;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic records with independent identity and ordinary create/read/delete UI. No model-specific guard. */
public final class RecordIdentityFixtureActivity extends Activity {
    private LinearLayout layout;
    private boolean atList;
    private static volatile String records = "[]";
    private static volatile int launchId;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (getIntent().getBooleanExtra("reset", false)) {
            records = "[{\"id\":\"old\",\"name\":\"Report27\",\"extension\":\".md\",\"quantity\":\"1 serving\",\"body\":\"beta\"}]";
        }
        launchId = getIntent().getIntExtra("launchId", 0);
        showList();
    }

    private void screen(String title) {
        atList = false;
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 16, 24, 16);
        layout.setFocusableInTouchMode(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        text(title);
        layout.requestFocus();
    }
    private void text(String value) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(18); layout.addView(view);
    }
    private void button(String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setOnClickListener(v -> action.run()); layout.addView(view);
    }
    private EditText field(String label, String hint, String value) {
        text(label); EditText view = new EditText(this); view.setContentDescription(label); view.setHint(hint); view.setText(value);
        view.setSingleLine(true); layout.addView(view); return view;
    }
    private JSONArray data() {
        try { return new JSONArray(records); } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
    }
    private void showList() {
        screen("Documents"); button("New document", this::create);
        atList = true;
        JSONArray data = data();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            button(item.optString("name"), () -> detail(item));
            text("File: " + item.optString("name") + item.optString("extension") + " | " + item.optString("quantity") + " | " + item.optString("body"));
        }
    }

    @Override public void onBackPressed() {
        if (atList) super.onBackPressed(); else showList();
    }
    private void create() {
        screen("New document");
        EditText name = field("Name", "Document name", "");
        EditText extension = field("Extension", ".md", ".md");
        EditText quantity = field("Quantity", "Quantity including unit", "");
        EditText body = field("Body", "Document content", "");
        button("Save", () -> {
            try {
                JSONObject item = new JSONObject().put("id", java.util.UUID.randomUUID().toString())
                    .put("name", name.getText().toString()).put("extension", extension.getText().toString())
                    .put("quantity", quantity.getText().toString()).put("body", body.getText().toString());
                records = data().put(item).toString(); detail(item);
            } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        });
        button("Cancel", this::showList);
        layout.requestFocus();
    }
    private void detail(JSONObject item) {
        screen(item.optString("name")); text("Record ID: " + item.optString("id"));
        text("File: " + item.optString("name") + item.optString("extension"));
        text("Quantity: " + item.optString("quantity")); text("Body: " + item.optString("body"));
        button("Documents", this::showList);
        button("Delete", () -> {
            JSONArray before = data(), after = new JSONArray();
            for (int i = 0; i < before.length(); i++) {
                JSONObject value = before.optJSONObject(i);
                if (!value.optString("id").equals(item.optString("id"))) after.put(value);
            }
            records = after.toString(); showList();
        });
    }

    /** Read-only host oracle. It is never provided to the model or used to steer its decisions. */
    public static final class StateProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor result = new MatrixCursor(new String[]{"records", "launchId"}); result.addRow(new Object[]{records, launchId}); return result;
        }
        @Override public String getType(Uri uri) { return "application/json"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
