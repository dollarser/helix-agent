package com.helix.app.ui

import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Null is unread, not off. Keep native sizing without exposing a false toggle or input target.
 * A fresh control on resolution starts at the stored value; later user changes animate normally.
 */
@Composable
@Suppress("FunctionName")
internal fun SettingsSwitch(
    checked: Boolean?,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    key(checked != null) {
        val pending = checked == null
        Switch(
            checked = checked ?: false,
            onCheckedChange = { if (!pending) onCheckedChange(it) },
            modifier = if (pending) Modifier.clearAndSetSemantics {}.then(modifier).alpha(0f) else modifier,
            enabled = enabled && !pending,
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun SettingsCheckbox(
    checked: Boolean?,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    key(checked != null) {
        val pending = checked == null
        Checkbox(
            checked = checked ?: false,
            onCheckedChange = { if (!pending) onCheckedChange(it) },
            modifier = if (pending) Modifier.clearAndSetSemantics {}.then(modifier).alpha(0f) else modifier,
            enabled = enabled && !pending,
        )
    }
}
