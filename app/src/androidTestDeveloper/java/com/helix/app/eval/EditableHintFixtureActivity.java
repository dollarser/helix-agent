package com.helix.app.eval;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native platform hint substitution with an independent actual-value receipt. */
public final class EditableHintFixtureActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setFocusableInTouchMode(true);
        EditText field = new EditText(this);
        field.setContentDescription("Fixture extension");
        field.setHint(".md");
        field.setText(".md");
        TextView receipt = new TextView(this);
        receipt.setContentDescription("Actual value receipt");
        receipt.setText("length=3;value=.md");
        field.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                receipt.setText("length=" + field.length() + ";value=" + field.getText().toString());
            }
            public void afterTextChanged(android.text.Editable e) {}
        });
        layout.addView(field);
        layout.addView(receipt);
        setContentView(layout);
        layout.requestFocus();
    }
}
