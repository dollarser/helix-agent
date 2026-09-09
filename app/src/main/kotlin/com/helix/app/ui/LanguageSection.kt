package com.helix.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore

/**
 * HXA-069: the app UI language selector (跟随系统 / 简体中文 / English). The choice is persisted
 * by [AppLanguageStore] and applied immediately: [AppLanguageStore.applyChoice] records it (and,
 * on API 33+, pushes it to the system per-app-locale store for two-way sync), then the host
 * activity is recreated so its `attachBaseContext` re-applies the locale via
 * [AppLanguageStore.wrapForLocale]. The option labels are endonyms (identical in every locale).
 */
@Composable
@Suppress("FunctionName")
internal fun LanguageSection() {
    val context = LocalContext.current
    val activity = findActivity(context)
    var current by remember { mutableStateOf(AppLanguageStore.stored(context)) }
    val options =
        listOf(
            AppLanguage.SYSTEM to stringResource(R.string.language_system),
            AppLanguage.ZH_CN to stringResource(R.string.language_zh_cn),
            AppLanguage.EN to stringResource(R.string.language_en),
        )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.settings_language_title),
            style = MaterialTheme.typography.titleMedium,
        )
        options.forEach { (choice, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = choice == current,
                    onClick = {
                        if (choice != current) {
                            current = choice
                            AppLanguageStore.applyChoice(context, choice)
                            activity?.recreate()
                        }
                    },
                    modifier = Modifier.testTag("settings-language-${choice.name}"),
                )
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * The host [Activity] for [android.app.Activity.recreate], unwrapping the [ContextWrapper] chain:
 * the activity's context is itself wrapped by [AppLanguageStore.wrapForLocale]
 * (`createConfigurationContext`), so a plain cast of [LocalContext] would miss it.
 */
private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
