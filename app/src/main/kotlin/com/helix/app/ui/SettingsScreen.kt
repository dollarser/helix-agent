package com.helix.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.egress.EgressRuleSection
import com.helix.app.language.AppLanguage
import com.helix.app.language.AppLanguageStore
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.SafetyProfileStore
import com.helix.app.provider.ProviderService
import com.helix.core.model.SafetyProfile
import com.helix.core.storage.repository.HighSensitivityRuleRepository

/**
 * The settings screen (HXA-028): the safety-profile section (ADR-0005/0006)
 * plus the provider-management section ([ProviderManager]).
 *
 * Profile rules:
 * - the CONSUMER build renders NO Advanced entry at all (ADR-0006: the
 *   consumer channel never offers a path from Standard into Advanced) and its
 *   store refuses any switch to ADVANCED (fail-closed);
 * - the DEVELOPER build offers the explicit switch, guarded by the in-app risk
 *   explanation (the profile_advanced_risk_summary resource) that states the ADR guarantees;
 * - the switch is a PURE state transition — M2 enables no capability from it
 *   (NFR-011: zero permission/Root/Runtime/network side effects), and the
 *   screen says so honestly instead of faking gated capabilities.
 *
 * HXA-068 adds the ADVANCED bounded high-sensitivity egress-rule section
 * ([EgressRuleSection]), shown ONLY when the profile is ADVANCED (and the
 * developer build offers Advanced); consumer/Standard never render it.
 */
@Composable
@Suppress("FunctionName", "LongMethod")
fun SettingsScreen(
    profileStore: SafetyProfileStore,
    providerService: ProviderService,
    egressRules: HighSensitivityRuleRepository,
) {
    val profile by profileStore.flow.collectAsStateWithLifecycle()
    var riskDialogOpen by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("screen-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_safety_section), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    if (profile == SafetyProfile.ADVANCED) {
                        stringResource(R.string.settings_current_advanced)
                    } else {
                        stringResource(R.string.settings_current_standard)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("settings-profile-current"),
                )
            }
            if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
                if (profile == SafetyProfile.STANDARD) {
                    OutlinedButton(
                        onClick = { riskDialogOpen = true },
                        modifier = Modifier.testTag("settings-advanced-switch"),
                    ) {
                        Text(stringResource(R.string.settings_switch_to_advanced))
                    }
                } else {
                    OutlinedButton(
                        onClick = { profileStore.switchTo(SafetyProfile.STANDARD) },
                        modifier = Modifier.testTag("settings-advanced-exit"),
                    ) {
                        Text(stringResource(R.string.settings_switch_back_standard))
                    }
                }
                Text(
                    stringResource(R.string.settings_advanced_m2_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-advanced-note"),
                )
            } else {
                Text(
                    stringResource(R.string.settings_consumer_standard_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings-advanced-absent"),
                )
            }
        }

        HorizontalDivider()

        LanguageSection()

        HorizontalDivider()

        ProviderManager(providerService)

        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE && profile == SafetyProfile.ADVANCED) {
            HorizontalDivider()
            EgressRuleSection(egressRules)
        }
    }

    if (riskDialogOpen) {
        AlertDialog(
            onDismissRequest = { riskDialogOpen = false },
            title = { Text(stringResource(R.string.settings_advanced_confirm_title)) },
            text = {
                Text(stringResource(R.string.profile_advanced_risk_summary))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        profileStore.switchTo(SafetyProfile.ADVANCED)
                        riskDialogOpen = false
                    },
                    modifier = Modifier.testTag("settings-risk-confirm"),
                ) {
                    Text(stringResource(R.string.settings_advanced_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { riskDialogOpen = false },
                    modifier = Modifier.testTag("settings-risk-cancel"),
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            modifier = Modifier.testTag("settings-risk-dialog"),
        )
    }
}

/**
 * HXA-069: the app UI language selector (跟随系统 / 简体中文 / English). The choice is persisted
 * by [AppLanguageStore] and applied immediately: [AppLanguageStore.applyChoice] records it (and,
 * on API 33+, pushes it to the system per-app-locale store for two-way sync), then the host
 * activity is recreated so its `attachBaseContext` re-applies the locale via
 * [AppLanguageStore.wrapForLocale]. The option labels are endonyms (identical in every locale).
 */
@Composable
@Suppress("FunctionName")
private fun LanguageSection() {
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
