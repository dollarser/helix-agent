package com.helix.app.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/** Success feedback is transient; errors and uncertain outcomes remain in their forms. */
internal fun showAccountToast(
    context: Context,
    @StringRes message: Int,
) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
