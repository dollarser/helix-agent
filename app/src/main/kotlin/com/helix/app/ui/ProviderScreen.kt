package com.helix.app.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.provider.ManagedProviderAccountResult
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import kotlinx.coroutines.launch

/**
 * Provider management UI (HXA-028; the task's “设置 → Provider” section).
 *
 * The UI dispatches intents to [ProviderService] and observes its [rows]
 * StateFlow — it never holds a network Job, never touches DAOs/OkHttp and
 * never sees a secret (only the [ProviderRowUi.hasKey] flag, NFR-007).
 *
 * Rules rendered here (all enforced in the pure/service layer):
 * - a provider is chat-selectable ONLY after a completed connection test
 *   (“未完成连接测试不贬为已可用”);
 * - a cleartext http endpoint shows the host:port risk display and requires
 *   the explicit per-host:port confirmation checkbox before save
 *   (doc 10 section 2.5; ADR-0005: no global cleartext switch);
 * - a test failure shows the SAFE phase + code label (FR-LLM-004 / doc 02
 *   section 13) — never a raw exception message.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
fun ProviderManager(providerService: ProviderService) {
    val rows by providerService.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var templatePickerOpen by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<ProviderForm?>(null) }
    var contextRow by remember { mutableStateOf<com.helix.app.provider.ProviderRowUi?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var accountFailureId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.provider_screen_title), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { templatePickerOpen = true },
                modifier = Modifier.testTag("provider-add"),
            ) {
                Text(stringResource(R.string.provider_add))
            }
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.provider_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEach { row ->
            ProviderRow(
                row = row,
                testing = testingId == row.id,
                actions =
                    ProviderRowActions(
                        onDeclareVision = { enabled ->
                            // The user-visible manual declaration (ADR-0014): vision may come from a
                            // real probe OR this explicit mark — the UI shows 「手动声明」 afterwards.
                            scope.launch { providerService.declareVisionCapability(row.id, enabled) }
                        },
                        onTest = {
                            if (testingId == null) {
                                testingId = row.id
                                scope.launch {
                                    try {
                                        providerService.runConnectionTest(row.id)
                                    } catch (e: Exception) {
                                        // A row that cannot even be resolved (corruption) fails
                                        // closed: the status stays 未测试, the row stays
                                        // non-selectable, and the exception is logged — never
                                        // rendered raw (doc 02 section 13).
                                        Log.w(TAG, "connection test for ${row.id} did not run", e)
                                    } finally {
                                        testingId = null
                                    }
                                }
                            }
                        },
                        onEdit = { modelOverride ->
                            // storedConfig is a Room read: it runs on the service's IO
                            // scope, never on this (UI) thread. HXA-059: a backend model
                            // id selected from the row's "后端可用模型" section prefills
                            // the form's model field — the form opens for EDITING, it
                            // is never auto-saved.
                            scope.launch {
                                try {
                                    val config = providerService.storedConfig(row.id)
                                    form =
                                        editingProviderForm(row, config, modelOverride)
                                } catch (e: Exception) {
                                    // A row that cannot be resolved (corruption) fails
                                    // closed: the edit is not opened and the exception
                                    // is logged — never rendered raw (doc 02 section 13).
                                    Log.w(TAG, "could not load provider ${row.id} for edit", e)
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                try {
                                    providerService.delete(row.id)
                                } catch (e: Exception) {
                                    Log.w(TAG, "could not delete provider ${row.id}", e)
                                }
                            }
                        },
                        onManageAccount = {
                            scope.launch {
                                accountFailureId =
                                    if (
                                        providerService.openManagedAccount(row.id) ==
                                        ManagedProviderAccountResult.OPENED
                                    ) {
                                        null
                                    } else {
                                        row.id
                                    }
                            }
                        },
                    ),
                accountUnavailable = accountFailureId == row.id,
            )
            TextButton({ contextRow = row }, Modifier.testTag("provider-context-${row.id}")) {
                Text(stringResource(R.string.chat_context_title))
            }
        }
    }

    contextRow?.let { ProviderContextDialog(it, providerService) { contextRow = null } }

    if (templatePickerOpen) {
        TemplatePickerDialog(
            onSelect = { template ->
                templatePickerOpen = false
                form =
                    ProviderForm(
                        providerId = null,
                        template = template,
                        fields =
                            ProviderForm.FormFields(
                                name = template.displayName,
                                endpoint = template.defaultEndpoint?.full.orEmpty(),
                                model = "",
                                headerName = "",
                                headerValue = "",
                                apiKey = "",
                            ),
                        hasStoredKey = false,
                        cleartextConfirmed = false,
                        error = null,
                    )
            },
            onDismiss = { templatePickerOpen = false },
        )
    }

    val currentForm = form
    if (currentForm != null) {
        ProviderFormDialog(
            form = currentForm,
            saving = saving,
            onField = { form = it },
            onDismiss = {
                // An in-flight save keeps running (its result is dropped below
                // when it no longer matches the form); only the dialog closes.
                form = null
            },
            onSave = {
                if (saving) return@ProviderFormDialog
                val target = currentForm
                saving = true
                scope.launch {
                    try {
                        val result = attemptSave(target, providerService)
                        // Stale-result guard: the form may have changed (or been
                        // closed) while the Room/Keystore write was in flight.
                        if (form == target) {
                            form =
                                when (result) {
                                    SaveResult.Saved -> null
                                    is SaveResult.Rejected -> target.copy(error = result)
                                }
                        }
                    } finally {
                        saving = false
                    }
                }
            },
        )
    }
}

private const val TAG = "HelixProviderUi"
