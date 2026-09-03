package com.helix.feature.browser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserTab
import com.helix.feature.browser.BrowserTabController
import com.helix.feature.browser.DownloadDenial
import com.helix.feature.browser.DownloadItem
import com.helix.feature.browser.DownloadStatus
import com.helix.feature.browser.LoadError

/**
 * The browser feature's Compose surface (HXA-060). Binds to the
 * [BrowserController]'s [androidx.coroutines.flow.StateFlow]s and calls its command
 * methods — this file never touches WebView, DAOs, OkHttp or QuickJS directly (AGENTS.md);
 * the only WebView reference is the opaque view [BrowserController.hostView] hands to
 * [AndroidView].
 *
 * Every interactive element carries a `browser-*` test tag; the on-device tests and the
 * UI tests drive the screen through them. Text is hardcoded Chinese — resource extraction
 * is HXA-067.
 */
@Composable
@Suppress("FunctionName")
fun BrowserScreen(controller: BrowserController) {
    val state by controller.state.collectAsState()
    val downloads by controller.downloads.collectAsState()
    val selected = state.selectedTab

    var urlText by remember(selected?.id, selected?.url) {
        mutableStateOf(selected?.url?.takeIf { it != BrowserTabController.ABOUT_BLANK } ?: "")
    }

    // The one SAF destination picker: a queued download is streamed only after the user
    // picks a document here (doc 09 §3.4: no auto-save, no default directory).
    val onChooseSaveLocation = rememberDownloadSaveLauncher(controller)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("browser-screen"),
    ) {
        TabStrip(state, controller)
        AddressBar(
            selected,
            controller,
            urlText,
            onUrlChange = { urlText = it },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                selected == null -> {
                    Text("打开一个新标签页", Modifier.padding(16.dp))
                }

                selected.error != null -> {
                    ErrorPage(selected, controller)
                }

                controller.hostView(selected.id) != null -> {
                    key(selected.id) {
                        AndroidView(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(2.dp),
                            factory = { checkNotNull(controller.hostView(selected.id)) },
                            onRelease = {
                                // Detaching the view from composition must NOT destroy the host:
                                // switching tabs keeps the WebView alive; only closeTab /
                                // clearHistory dispose it.
                            },
                        )
                    }
                }

                else -> {
                    Text("输入地址开始浏览", Modifier.padding(16.dp))
                }
            }
        }
        if (downloads.isNotEmpty()) {
            DownloadsPanel(downloads, controller, onChooseSaveLocation)
        }
        ClearRow(controller)
    }
}

/**
 * The SAF CreateDocument picker shared by every download row.
 * [rememberLauncherForActivityResult] must live at composition top level, so the picker and
 * its pending-item state are kept here and only the resulting callback reaches the panel.
 */
@Composable
@Suppress("FunctionName")
private fun rememberDownloadSaveLauncher(controller: BrowserController): (DownloadItem) -> Unit {
    val pendingSaveId = remember { mutableStateOf<String?>(null) }
    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
            val id = pendingSaveId.value
            pendingSaveId.value = null
            if (uri != null && id != null) controller.saveDownload(id, uri)
        }
    return { item ->
        pendingSaveId.value = item.id
        saveLauncher.launch(item.fileName)
    }
}

@Composable
@Suppress("FunctionName")
private fun TabStrip(
    state: BrowserTabController.State,
    controller: BrowserController,
) {
    Row(
        modifier =
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.tabs.forEach { tab ->
            Surface(
                onClick = { controller.select(tab.id) },
                modifier = Modifier.testTag("browser-tab-${tab.id}"),
                shape = MaterialTheme.shapes.small,
                color =
                    if (tab.id == state.selectedId) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tab.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                    )
                    TextButton(
                        onClick = { controller.closeTab(tab.id) },
                        modifier = Modifier.testTag("browser-tab-close-${tab.id}"),
                        contentPadding = PaddingValues(4.dp),
                    ) {
                        Text("×")
                    }
                }
            }
        }
        TextButton(onClick = { controller.newTab() }, modifier = Modifier.testTag("browser-tab-new")) {
            Text("＋")
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AddressBar(
    selected: BrowserTab?,
    controller: BrowserController,
    urlText: String,
    onUrlChange: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { selected?.let { controller.goBack(it.id) } },
            enabled = selected != null && selected.canGoBack && !selected.isLoading,
            modifier = Modifier.testTag("browser-back"),
        ) {
            Text("←")
        }
        TextButton(
            onClick = { selected?.let { controller.goForward(it.id) } },
            enabled = selected != null && selected.canGoForward && !selected.isLoading,
            modifier = Modifier.testTag("browser-forward"),
        ) {
            Text("→")
        }
        OutlinedTextField(
            value = urlText,
            onValueChange = onUrlChange,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("browser-url-field"),
            singleLine = true,
            placeholder = { Text("输入网址") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions =
                KeyboardActions(
                    onGo = { selected?.let { controller.navigate(it.id, urlText) } },
                    onDone = { selected?.let { controller.navigate(it.id, urlText) } },
                ),
        )
        if (selected?.isLoading == true) {
            TextButton(
                onClick = { selected?.let { controller.stop(it.id) } },
                modifier = Modifier.testTag("browser-stop"),
            ) {
                Text("停止")
            }
        } else {
            TextButton(
                onClick = { selected?.let { controller.navigate(it.id, urlText) } },
                enabled = selected != null,
                modifier = Modifier.testTag("browser-go"),
            ) {
                Text("前往")
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ErrorPage(
    tab: BrowserTab,
    controller: BrowserController,
) {
    val error = tab.error ?: return
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .testTag("browser-error-page"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(error.userMessage)
        // Retry only for loads the WebView attempted and failed; a policy denial of the
        // same URL is a stable outcome and retrying would just re-denied it.
        if (error is LoadError) {
            Button(
                onClick = { controller.retry(tab.id) },
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .testTag("browser-retry"),
            ) {
                Text("重试")
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun DownloadsPanel(
    downloads: List<DownloadItem>,
    controller: BrowserController,
    onSaveClick: (DownloadItem) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
    ) {
        Text("下载", style = MaterialTheme.typography.labelLarge)
        downloads.forEach { item ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .testTag("browser-download-${item.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        downloadStatusText(item),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (item.status) {
                    DownloadStatus.PENDING_CHOICE -> {
                        TextButton(
                            onClick = { onSaveClick(item) },
                            modifier = Modifier.testTag("browser-download-save-${item.id}"),
                        ) {
                            Text("选择位置")
                        }
                    }

                    DownloadStatus.SAVED, DownloadStatus.FAILED, DownloadStatus.DENIED -> {
                        TextButton(
                            onClick = { controller.dismissDownload(item.id) },
                            modifier = Modifier.testTag("browser-download-dismiss-${item.id}"),
                        ) {
                            Text("关闭")
                        }
                    }

                    DownloadStatus.SAVING -> {
                        Unit
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ClearRow(controller: BrowserController) {
    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        TextButton(
            onClick = { controller.clearCookies() },
            modifier = Modifier.testTag("browser-clear-cookies"),
        ) {
            Text("清除Cookie")
        }
        TextButton(
            onClick = { controller.clearCache() },
            modifier = Modifier.testTag("browser-clear-cache"),
        ) {
            Text("清除缓存")
        }
        TextButton(
            onClick = { controller.clearHistory() },
            modifier = Modifier.testTag("browser-clear-history"),
        ) {
            Text("清除历史")
        }
    }
}

private fun downloadStatusText(item: DownloadItem): String =
    when (item.status) {
        DownloadStatus.PENDING_CHOICE -> "待选择保存位置"
        DownloadStatus.SAVING -> "保存中…"
        DownloadStatus.SAVED -> "已保存"
        DownloadStatus.FAILED -> "失败：${item.detail ?: "未知原因"}"
        DownloadStatus.DENIED -> "已拒绝：${denialText(item.denial)}"
    }

private fun denialText(denial: DownloadDenial?): String =
    when (denial) {
        DownloadDenial.URL -> "该 URL 不可下载"
        DownloadDenial.UNSAFE_TYPE -> "禁止的文件类型"
        DownloadDenial.SIZE -> "超过 100 MiB 上限"
        DownloadDenial.NAME -> "文件名无效"
        null -> "未知原因"
    }
