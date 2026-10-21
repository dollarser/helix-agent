package com.helix.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Drawer uses the application's shared palette and typography, with a quiet navigation surface. */
@Composable
@Suppress("FunctionName")
internal fun DrawerVisualTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = base.copy(surface = base.surfaceContainerLow), content = content)
}

/** 48dp touch target, 24dp corners, 20dp icons and 14dp icon/text spacing. */
@Composable
@Suppress("FunctionName")
internal fun DrawerRow(
    label: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    badge: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { this.selected = selected },
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor =
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            iconRes?.let { Icon(painterResource(it), null, Modifier.size(20.dp).testTag("drawer-icon-$it")) }
            androidx.compose.foundation.layout
                .Box(Modifier.weight(1f)) { label() }
            badge?.invoke()
        }
    }
}

@DrawableRes
internal fun drawerIcon(route: String): Int =
    when (route.substringBefore('/')) {
        "projects", "project" -> R.drawable.ic_files_folder
        "models" -> R.drawable.ic_drawer_models
        "extensions" -> R.drawable.ic_drawer_plugins
        "settings", "setup" -> R.drawable.ic_drawer_settings
        "files" -> R.drawable.ic_files_document
        "knowledge" -> R.drawable.ic_drawer_book
        "reminders" -> R.drawable.ic_drawer_bell
        "tasks" -> R.drawable.ic_drawer_tasks
        "artifacts" -> R.drawable.ic_files_document
        "browser" -> R.drawable.ic_drawer_browser
        "terminal" -> R.drawable.ic_drawer_terminal
        else -> R.drawable.ic_drawer_work
    }

@Composable
@Suppress("FunctionName")
internal fun DrawerChevron(expanded: Boolean? = null) {
    Icon(
        painterResource(R.drawable.ic_drawer_chevron),
        null,
        Modifier.size(16.dp).then(
            if (expanded == null) Modifier else Modifier.rotate(if (expanded) 270f else 90f),
        ),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
