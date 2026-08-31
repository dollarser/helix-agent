package com.helix.app.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.provider.ComposeOutcome
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderComposer
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.catalog.ProviderTemplate
import com.helix.provider.catalog.ProviderTemplateCatalog
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
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
fun ProviderManager(providerService: ProviderService) {
    val rows by providerService.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var templatePickerOpen by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<ProviderForm?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Provider 配置", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { templatePickerOpen = true },
                modifier = Modifier.testTag("provider-add"),
            ) {
                Text("添加 Provider")
            }
        }
        if (rows.isEmpty()) {
            Text(
                "尚无 Provider。从模板添加一个，并通过连接测试后才能用于会话。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEach { row ->
            ProviderRow(
                row = row,
                testing = testingId == row.id,
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
                onEdit = {
                    // storedConfig is a Room read: it runs on the service's IO
                    // scope, never on this (UI) thread.
                    scope.launch {
                        try {
                            val config = providerService.storedConfig(row.id)
                            form =
                                ProviderForm(
                                    providerId = row.id,
                                    template = editTemplateFor(config.protocol, config.endpoint),
                                    fields =
                                        ProviderForm.FormFields(
                                            name = config.displayName,
                                            endpoint = config.endpoint.full,
                                            model = config.model,
                                            headerName = "",
                                            headerValue = "",
                                            apiKey = "",
                                        ),
                                    hasStoredKey = row.hasKey,
                                    cleartextConfirmed = false,
                                    error = null,
                                )
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
            )
        }
    }

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
                                    is SaveResult.Rejected -> target.copy(error = result.reason)
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

/** The un-persisted provider form (create when [providerId] is null). */
private data class ProviderForm(
    val providerId: String?,
    val template: ProviderTemplate,
    val fields: FormFields,
    val hasStoredKey: Boolean,
    val cleartextConfirmed: Boolean,
    val error: String?,
) {
    data class FormFields(
        val name: String,
        val endpoint: String,
        val model: String,
        val headerName: String,
        val headerValue: String,
        val apiKey: String,
    )
}

/** The outcome of a save attempt; [SaveResult.Rejected.reason] is user-visible Chinese. */
private sealed interface SaveResult {
    data object Saved : SaveResult

    data class Rejected(
        val reason: String,
    ) : SaveResult
}

@Composable
@Suppress("FunctionName")
private fun TemplatePickerDialog(
    onSelect: (ProviderTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 Provider 模板") },
        text = {
            Column(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 400.dp),
            ) {
                ProviderTemplateCatalog.all.forEach { template ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(template) }
                                .testTag("provider-template-${template.id}")
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(template.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${UiLabels.protocolLabel(template.protocol)} · " +
                                    (if (template.credentialRequired) "需要 API Key" else "Key 可选"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        modifier = Modifier.testTag("provider-template-picker"),
    )
}

// The Compose DSL keeps the whole form (template fields, endpoint parse
// feedback, key entry, the cleartext risk box, the save gate) in one
// composable; detekt's size/complexity rules do not model UI composition,
// so both are suppressed per composable (same convention as the app shell).
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
private fun ProviderFormDialog(
    form: ProviderForm,
    saving: Boolean,
    onField: (ProviderForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cleartext =
        remember(form.fields.endpoint) {
            tryParseEndpoint(form.fields.endpoint)?.let { CleartextAuthorization.requiredFor(it) }
        }
    val keyOk =
        !form.template.credentialRequired ||
            form.fields.apiKey.isNotBlank() ||
            form.hasStoredKey
    val saveEnabled =
        !saving && form.error == null && (cleartext == null || form.cleartextConfirmed) && keyOk
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (form.providerId == null) "添加 Provider" else "编辑 Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (form.providerId == null) {
                    form.template.notes.forEach { note ->
                        Text(
                            "模板说明：$note",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = form.fields.name,
                    onValueChange = { onField(form.copy(fields = form.fields.copy(name = it))) },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.testTag("provider-form-name"),
                )
                OutlinedTextField(
                    value = form.fields.endpoint,
                    onValueChange = { onField(form.copy(fields = form.fields.copy(endpoint = it))) },
                    label = { Text("Endpoint（http/https 根地址）") },
                    singleLine = true,
                    modifier = Modifier.testTag("provider-form-endpoint"),
                )
                OutlinedTextField(
                    value = form.fields.model,
                    onValueChange = { onField(form.copy(fields = form.fields.copy(model = it))) },
                    label = { Text("模型 ID") },
                    singleLine = true,
                    modifier = Modifier.testTag("provider-form-model"),
                )
                OutlinedTextField(
                    value = form.fields.headerName,
                    onValueChange = {
                        onField(form.copy(fields = form.fields.copy(headerName = it)))
                    },
                    label = { Text("自定义 header 名称（可选）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.fields.headerValue,
                    onValueChange = {
                        onField(form.copy(fields = form.fields.copy(headerValue = it)))
                    },
                    label = { Text("自定义 header 值（可选）") },
                    singleLine = true,
                )
                if (form.template.credentialRequired) {
                    OutlinedTextField(
                        value = form.fields.apiKey,
                        onValueChange = {
                            onField(form.copy(fields = form.fields.copy(apiKey = it)))
                        },
                        label = {
                            Text(if (form.hasStoredKey) "API Key（留空保持现有）" else "API Key")
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.testTag("provider-form-key"),
                    )
                }
                if (cleartext != null) {
                    Text(
                        "明文 HTTP：请求将不加密发往 ${cleartext.host}:${cleartext.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = form.cleartextConfirmed,
                            onCheckedChange = { onField(form.copy(cleartextConfirmed = it)) },
                            modifier = Modifier.testTag("provider-cleartext-confirm"),
                        )
                        Text(
                            "我已了解该 host:port 的明文传输风险并确认授权",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                form.error?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = saveEnabled,
                modifier = Modifier.testTag("provider-form-save"),
            ) {
                Text(if (saving) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("provider-form-cancel"),
            ) {
                Text("取消")
            }
        },
        modifier = Modifier.testTag("provider-form-dialog"),
    )
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun ProviderRow(
    row: ProviderRowUi,
    testing: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("provider-row")
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(row.status)
        }
        Text(
            "${UiLabels.displayOrigin(row.origin)} · ${UiLabels.residenceLabel(row.residence)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildString {
                append("模型：${row.model} · ${UiLabels.protocolLabel(row.protocol)}")
                if (row.hasKey) append(" · 已配置 Key")
                if (row.isCleartext) append(" · 明文 HTTP")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val detail = statusDetail(row)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onTest,
                enabled = !testing,
                modifier = Modifier.testTag("provider-test"),
            ) {
                Text(if (testing) "测试中…" else "连接测试")
            }
            TextButton(onClick = onEdit, modifier = Modifier.testTag("provider-edit")) {
                Text("编辑")
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag("provider-delete"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text("删除")
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun StatusChip(status: ConnectionTestStatus) {
    val (color, tag) =
        when (status) {
            ConnectionTestStatus.Untested -> {
                MaterialTheme.colorScheme.surfaceVariant to "provider-status-untested"
            }

            is ConnectionTestStatus.Passed -> {
                MaterialTheme.colorScheme.primaryContainer to "provider-status-passed"
            }

            is ConnectionTestStatus.Failed -> {
                MaterialTheme.colorScheme.errorContainer to "provider-status-failed"
            }
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            status.chipText(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** The status detail line under a provider row (safe labels only). */
private fun statusDetail(row: ProviderRowUi): String? =
    when (val status = row.status) {
        ConnectionTestStatus.Untested -> {
            "尚未通过连接测试，暂不能用于会话"
        }

        is ConnectionTestStatus.Passed -> {
            if (row.capabilityChips.isEmpty()) {
                null
            } else {
                "能力：${row.capabilityChips.joinToString("  ")}"
            }
        }

        is ConnectionTestStatus.Failed -> {
            "失败阶段：${ConnectionTestMapping.phaseLabel(status.phase)} — ${status.codeLabel}" +
                (if (status.retryable) "（可重试）" else "")
        }
    }

/**
 * The template an edit dialog composes against. The persisted row does not
 * store the template id, so the template is re-resolved from the protocol: an
 * exact endpoint match (same template family) wins, then the generic (no
 * default endpoint) template of the protocol, then any template of the
 * protocol. For a re-pointed endpoint the generic template's headers replace
 * the old attribution headers — the honest outcome, since the endpoint is no
 * longer that vendor's.
 */
private fun editTemplateFor(
    protocol: ProviderProtocol,
    endpoint: NormalizedEndpoint,
): ProviderTemplate {
    val candidates = ProviderTemplateCatalog.all.filter { it.protocol == protocol }
    return candidates.firstOrNull { it.defaultEndpoint == endpoint }
        ?: candidates.firstOrNull { it.defaultEndpoint == null }
        ?: candidates.first()
}

/**
 * The parse failure is INTENTIONALLY converted to null: the form only needs a
 * cleartext hint for a parseable endpoint; unparseable input is rejected later
 * by the composer with its user-visible reason (doc 02 section 13).
 */
@Suppress("SwallowedException")
private fun tryParseEndpoint(raw: String): NormalizedEndpoint? =
    try {
        NormalizedEndpoint.parse(raw)
    } catch (e: IllegalArgumentException) {
        null
    }

/**
 * The service `require(...)` failures are deliberately converted into the
 * form's error text: the dialog stays open so the user can correct the input,
 * and the internal (English) exception message is never shown raw (doc 02
 * section 13).
 */
@Suppress("SwallowedException")
private suspend fun attemptSave(
    form: ProviderForm,
    providerService: ProviderService,
): SaveResult =
    try {
        applySave(form, providerService)
    } catch (e: IllegalArgumentException) {
        SaveResult.Rejected("保存失败：请检查输入后重试")
    }

/**
 * Composes + validates the form, then persists (single fail-closed result).
 * The persist calls are the service's suspend Room/Keystore operations; this
 * runs on the caller's coroutine, which the UI scope hops into the service's
 * IO scope for ([ProviderService.create]/[ProviderService.update]).
 */
private suspend fun applySave(
    form: ProviderForm,
    providerService: ProviderService,
): SaveResult {
    val headers =
        if (form.fields.headerName.isNotBlank()) {
            mapOf(form.fields.headerName.trim() to form.fields.headerValue.trim())
        } else {
            emptyMap()
        }
    val outcome =
        ProviderComposer.compose(
            form.template,
            form.fields.name.trim(),
            form.fields.endpoint.trim(),
            form.fields.model.trim(),
            headers,
        )
    val failure =
        when (outcome) {
            is ComposeOutcome.Rejected -> {
                outcome.reason
            }

            is ComposeOutcome.Ok -> {
                val draft = outcome.draft
                val key =
                    form.fields.apiKey
                        .trim()
                        .takeIf { it.isNotEmpty() }
                when {
                    draft.credentialRequired && key == null && !form.hasStoredKey -> {
                        "该 Provider 需要 API Key"
                    }

                    draft.isCleartext && !form.cleartextConfirmed -> {
                        "请先确认该 host:port 的明文传输风险"
                    }

                    else -> {
                        if (form.providerId == null) {
                            providerService.create(draft, key, form.cleartextConfirmed)
                        } else {
                            providerService.update(form.providerId, draft, key, form.cleartextConfirmed)
                        }
                        null
                    }
                }
            }
        }
    return if (failure == null) SaveResult.Saved else SaveResult.Rejected(failure)
}

private const val TAG = "HelixProviderUi"
