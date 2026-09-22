package com.helix.feature.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserTab
import com.helix.feature.browser.BrowserTabController
import com.helix.feature.browser.ContextMenuData
import com.helix.feature.browser.DownloadItem
import com.helix.feature.browser.DownloadRequest
import com.helix.feature.browser.DownloadStatus
import com.helix.feature.browser.LoadError
import com.helix.feature.browser.R
import com.helix.feature.browser.engine.SearchEngines

/**
 * The redesigned, feature-rich Compose surface for Helix Browser, referencing Via and X Browser:
 * - Minimalist, modern Omnibox top bar with SSL lock, progress bar, and smart input resolution
 * - Classic 5-button bottom bar: [Back], [Forward], [Home], [Tabs], [Menu]
 * - Minimalist Via-style start page with Search engine dropdown & Speed Dials
 * - Full card-grid visual Tab Switcher with Incognito mode
 * - Action-packed Menu sheet with Bookmarks, History, Downloads, Find in page, UserScripts, DevTools, Reader mode
 * - Hardware BackHandler stack
 * - 100% preservation of existing test tags and Agent tool capabilities
 */
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
fun BrowserScreen(controller: BrowserController) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val downloads by controller.downloads.collectAsState()
    val preferences by controller.preferences.collectAsState()
    val bookmarks by controller.bookmarks.collectAsState()
    val history by controller.history.collectAsState()
    val speedDials by controller.speedDials.collectAsState()
    val scripts by controller.scripts.collectAsState()
    val findState by controller.findState.collectAsState()
    val blockedAdsCount by controller.adBlockedCount.collectAsState()

    val selected = state.selectedTab

    var urlText by remember(selected?.id, selected?.url) {
        mutableStateOf(selected?.url?.takeIf { it != BrowserTabController.ABOUT_BLANK } ?: "")
    }

    // Modal Sheet & Dialog States
    var showTabSwitcher by remember { mutableStateOf(false) }
    var showMenuSheet by remember { mutableStateOf(false) }
    var showBookmarksHistory by remember { mutableStateOf(false) }
    var bookmarksHistoryInitialTab by remember { mutableStateOf(0) }
    var showUserScripts by remember { mutableStateOf(false) }
    var sourceDialogContent by remember { mutableStateOf<String?>(null) }
    var readerDialogContent by remember { mutableStateOf<Pair<String, String>?>(null) }
    val contextMenu by controller.contextMenu.collectAsState()

    // SAF Destination Picker for Downloads
    val onChooseSaveLocation = rememberDownloadSaveLauncher(controller)

    // BackHandler: priority back stack
    val isAnyModalOpen =
        showTabSwitcher || showMenuSheet || showBookmarksHistory ||
            showUserScripts || sourceDialogContent != null || readerDialogContent != null ||
            contextMenu != null
    val canGoBackInTab = selected != null && selected.canGoBack
    val isNotOnHome = selected != null && selected.url != BrowserTabController.ABOUT_BLANK

    BackHandler(
        enabled = isAnyModalOpen || findState.isSearching || canGoBackInTab || isNotOnHome,
    ) {
        when {
            contextMenu != null -> controller.clearContextMenu()
            showTabSwitcher -> showTabSwitcher = false
            showMenuSheet -> showMenuSheet = false
            showBookmarksHistory -> showBookmarksHistory = false
            showUserScripts -> showUserScripts = false
            sourceDialogContent != null -> sourceDialogContent = null
            readerDialogContent != null -> readerDialogContent = null
            findState.isSearching -> selected?.let { controller.closeFindInPage(it.id) }
            canGoBackInTab -> controller.goBack(checkNotNull(selected).id)
            isNotOnHome -> controller.navigate(checkNotNull(selected).id, BrowserTabController.ABOUT_BLANK)
        }
    }

    val isTyping =
        urlText.isNotBlank() && urlText != selected?.url &&
            urlText != BrowserTabController.ABOUT_BLANK
    val suggestions =
        remember(urlText, bookmarks, history, isTyping) {
            if (!isTyping) {
                emptyList()
            } else {
                val q = urlText.trim().lowercase()
                val bms =
                    bookmarks
                        .filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
                        .take(3)
                        .map { Triple(it.title, it.url, true) }
                val hists =
                    history
                        .filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
                        .take(4)
                        .map { Triple(it.title, it.url, false) }
                (bms + hists).distinctBy { it.second }.take(5)
            }
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("screen-browser"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Omnibox / TopBar
            BrowserTopBar(
                selectedTab = selected,
                urlText = urlText,
                onUrlChange = { urlText = it },
                onSubmitUrl = {
                    val tabId = selected?.id ?: controller.newTab()
                    controller.smartNavigate(tabId, urlText)
                },
                onReload = { selected?.let { controller.reload(it.id) } },
                onStop = { selected?.let { controller.stop(it.id) } },
                onHome = {
                    val tabId = selected?.id ?: controller.newTab()
                    controller.navigate(tabId, BrowserTabController.ABOUT_BLANK)
                },
            )

            if (suggestions.isNotEmpty()) {
                OmniboxSuggestions(
                    suggestions = suggestions,
                    onSelectSuggestion = { chosenUrl ->
                        urlText = chosenUrl
                        val tabId = selected?.id ?: controller.newTab()
                        controller.navigate(tabId, chosenUrl)
                    },
                )
            }

            // 2. Central Web Content or Via-style Home Page
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                when {
                    selected == null || selected.url == BrowserTabController.ABOUT_BLANK -> {
                        BrowserHomePage(
                            speedDials = speedDials,
                            currentSearchEngine = SearchEngines.getById(preferences.searchEngineId),
                            blockedAdsCount = blockedAdsCount,
                            onSearchEngineSelect = { controller.setSearchEngine(it) },
                            onOpenUrl = { targetUrl ->
                                val tabId = selected?.id ?: controller.newTab()
                                controller.navigate(tabId, targetUrl)
                            },
                            onAddSpeedDial = { title, url -> controller.addSpeedDial(title, url) },
                            onRemoveSpeedDial = { controller.removeSpeedDial(it) },
                            onSearchClick = { /* Focus Omnibox handled via urlText */ },
                        )
                    }

                    selected.error != null -> {
                        ErrorPage(selected, controller)
                    }

                    controller.hostView(selected.id) != null -> {
                        key(selected.id, controller.hostView(selected.id)) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { checkNotNull(controller.hostView(selected.id)) },
                                onRelease = {},
                            )
                        }
                    }

                    else -> {
                        Text(
                            stringResource(R.string.browser_enter_address),
                            Modifier.padding(16.dp),
                        )
                    }
                }
            }

            // 3. Downloads Panel
            if (downloads.isNotEmpty()) {
                DownloadsPanel(downloads, controller, onChooseSaveLocation)
            }

            // 4. Find In Page Floating Bar
            if (findState.isSearching && selected != null) {
                BrowserFindInPageBar(
                    state = findState,
                    onQueryChange = { controller.findInPage(selected.id, it) },
                    onNext = { controller.findNext(selected.id, true) },
                    onPrevious = { controller.findNext(selected.id, false) },
                    onClose = { controller.closeFindInPage(selected.id) },
                )
            }

            // 5. Clear Row (Preserves test tags: browser-clear-cookies, browser-clear-cache, browser-clear-history)
            ClearRow(controller)

            // 6. Classic 5-Button Bottom Bar
            BrowserBottomBar(
                selectedTab = selected,
                tabCount = state.tabs.size,
                onBack = { selected?.let { controller.goBack(it.id) } },
                onForward = { selected?.let { controller.goForward(it.id) } },
                onHome = {
                    val tabId = selected?.id ?: controller.newTab()
                    controller.navigate(tabId, BrowserTabController.ABOUT_BLANK)
                },
                onTabsClick = { showTabSwitcher = true },
                onMenuClick = { showMenuSheet = true },
            )
        }

        // Modals & Sheets
        if (showTabSwitcher) {
            BrowserTabSwitcher(
                state = state,
                onSelectTab = { controller.select(it) },
                onCloseTab = { controller.closeTab(it) },
                onNewTab = { isIncognito -> controller.newTab(isIncognito) },
                onCloseAllTabs = { controller.closeAllTabs() },
                onDismiss = { showTabSwitcher = false },
            )
        }

        if (showMenuSheet) {
            val isCurrentBookmarked =
                remember(selected?.url, bookmarks) {
                    selected?.url != null && controller.isBookmarked(selected.url)
                }

            BrowserMenuSheet(
                selectedTab = selected,
                preferences = preferences,
                isBookmarked = isCurrentBookmarked,
                onToggleBookmark = {
                    selected?.let { tab ->
                        if (isCurrentBookmarked) {
                            bookmarks.firstOrNull { it.url == tab.url }?.let {
                                controller.removeBookmark(it.id)
                            }
                        } else {
                            controller.addBookmark(tab.title ?: tab.url, tab.url)
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.browser_bookmark_added),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                },
                onCopyUrl = {
                    selected?.url?.let { url ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("URL", url))
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.browser_url_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                },
                onShareUrl = {
                    selected?.url?.let { currentUrl ->
                        if (currentUrl.isNotBlank() && currentUrl != BrowserTabController.ABOUT_BLANK) {
                            val sendIntent =
                                Intent(Intent.ACTION_SEND).apply {
                                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                                    if (!selected.title.isNullOrBlank()) {
                                        putExtra(Intent.EXTRA_SUBJECT, selected.title)
                                    }
                                    type = "text/plain"
                                }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }
                    }
                },
                onOpenBookmarks = {
                    bookmarksHistoryInitialTab = 0
                    showBookmarksHistory = true
                },
                onOpenHistory = {
                    bookmarksHistoryInitialTab = 1
                    showBookmarksHistory = true
                },
                onOpenDownloads = {
                    // Downloads are shown in DownloadsPanel
                    Toast.makeText(context, "下载管理", Toast.LENGTH_SHORT).show()
                },
                onStartFindInPage = {
                    selected?.let { controller.findInPage(it.id, "") }
                },
                onToggleDesktopMode = {
                    selected?.let {
                        controller.setDesktopMode(it.id, !it.isDesktopMode)
                    }
                },
                onToggleNightMode = {
                    controller.toggleNightMode()
                },
                onToggleNoImageMode = {
                    controller.toggleNoImageMode()
                },
                onToggleAdBlock = {
                    controller.toggleAdBlock()
                },
                onOpenUserScripts = {
                    showUserScripts = true
                },
                onOpenDevTools = {
                    selected?.let { controller.injectEruda(it.id) }
                },
                onViewSource = {
                    selected?.let { tab ->
                        controller.extractSource(tab.id) { source ->
                            sourceDialogContent = source
                        }
                    }
                },
                onReaderMode = {
                    selected?.let { tab ->
                        controller.extractReader(tab.id) { title, content ->
                            readerDialogContent = Pair(title, content)
                        }
                    }
                },
                onClearCookies = { controller.clearCookies() },
                onClearCache = { controller.clearCache() },
                onClearHistory = { controller.clearHistory() },
                onDismiss = { showMenuSheet = false },
            )
        }

        if (showBookmarksHistory) {
            BrowserBookmarksHistoryDialog(
                initialTab = bookmarksHistoryInitialTab,
                bookmarks = bookmarks,
                history = history,
                onSelectUrl = { targetUrl ->
                    val tabId = selected?.id ?: controller.newTab()
                    controller.navigate(tabId, targetUrl)
                    showBookmarksHistory = false
                },
                onDeleteBookmark = { controller.removeBookmark(it) },
                onDeleteHistoryItem = { controller.removeHistory(it) },
                onClearAllHistory = { controller.clearBrowsingHistory() },
                onDismiss = { showBookmarksHistory = false },
            )
        }

        if (showUserScripts) {
            BrowserUserScriptsDialog(
                scripts = scripts,
                onToggleScript = { controller.toggleUserScript(it) },
                onSaveScript = { controller.saveUserScript(it) },
                onDeleteScript = { controller.deleteUserScript(it) },
                onDismiss = { showUserScripts = false },
            )
        }

        sourceDialogContent?.let { source ->
            BrowserSourceViewerDialog(
                source = source,
                onDismiss = { sourceDialogContent = null },
            )
        }

        readerDialogContent?.let { (title, content) ->
            BrowserReaderView(
                title = title,
                content = content,
                onDismiss = { readerDialogContent = null },
            )
        }

        contextMenu?.let { menu ->
            ContextMenuDialog(
                menu = menu,
                context = context,
                controller = controller,
                onDismiss = { controller.clearContextMenu() },
            )
        }
    }
}

/**
 * The SAF CreateDocument picker shared by every download row.
 */
@Composable
@Suppress("FunctionName")
private fun rememberDownloadSaveLauncher(controller: BrowserController): (DownloadItem) -> Unit {
    val pendingSaveId = remember { mutableStateOf<String?>(null) }
    val saveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
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
private fun ErrorPage(
    tab: BrowserTab,
    controller: BrowserController,
) {
    val context = LocalContext.current
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
        Text(error.userMessage(context))
        if (error is LoadError) {
            Button(
                onClick = { controller.retry(tab.id) },
                modifier =
                    Modifier
                        .padding(top = 12.dp)
                        .testTag("browser-retry"),
            ) {
                Text(stringResource(R.string.browser_retry))
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
    val context = LocalContext.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
    ) {
        Text(stringResource(R.string.browser_downloads_title), style = MaterialTheme.typography.labelLarge)
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
                        downloadStatusText(context, item),
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
                            Text(stringResource(R.string.browser_choose_location))
                        }
                    }

                    DownloadStatus.SAVED, DownloadStatus.FAILED, DownloadStatus.DENIED -> {
                        TextButton(
                            onClick = { controller.dismissDownload(item.id) },
                            modifier = Modifier.testTag("browser-download-dismiss-${item.id}"),
                        ) {
                            Text(stringResource(R.string.browser_dismiss))
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
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = { controller.clearCookies() },
            modifier = Modifier.testTag("browser-clear-cookies"),
        ) {
            Text(stringResource(R.string.browser_clear_cookies), fontSize = 11.sp)
        }
        TextButton(
            onClick = { controller.clearCache() },
            modifier = Modifier.testTag("browser-clear-cache"),
        ) {
            Text(stringResource(R.string.browser_clear_cache), fontSize = 11.sp)
        }
        TextButton(
            onClick = { controller.clearHistory() },
            modifier = Modifier.testTag("browser-clear-history"),
        ) {
            Text(stringResource(R.string.browser_clear_history), fontSize = 11.sp)
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun ContextMenuDialog(
    menu: ContextMenuData,
    context: Context,
    controller: BrowserController,
    onDismiss: () -> Unit,
) {
    val title =
        when (menu) {
            is ContextMenuData.Link -> menu.url
            is ContextMenuData.Image -> menu.imageUrl
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (menu) {
                    is ContextMenuData.Link -> {
                        TextButton(
                            onClick = {
                                val id = controller.newTab()
                                controller.navigate(id, menu.url)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browser_context_open_in_new_tab))
                        }
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("URL", menu.url))
                                Toast.makeText(context, R.string.browser_url_copied, Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browser_context_copy_link))
                        }
                        TextButton(
                            onClick = {
                                val sendIntent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        putExtra(Intent.EXTRA_TEXT, menu.url)
                                        type = "text/plain"
                                    }
                                context.startActivity(Intent.createChooser(sendIntent, null))
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browser_context_share_link))
                        }
                    }

                    is ContextMenuData.Image -> {
                        TextButton(
                            onClick = {
                                val id = controller.newTab()
                                controller.navigate(id, menu.imageUrl)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browser_context_view_image))
                        }
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Image URL", menu.imageUrl))
                                Toast.makeText(context, R.string.browser_url_copied, Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browser_context_copy_image_url))
                        }
                        TextButton(
                            onClick = {
                                controller.requestDownload(
                                    DownloadRequest(
                                        url = menu.imageUrl,
                                        suggestedName = "image_${System.currentTimeMillis()}.png",
                                        mimeType = "image/*",
                                        contentLength = -1L,
                                    ),
                                )
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browser_context_download_image))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_cancel))
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun OmniboxSuggestions(
    suggestions: List<Triple<String, String, Boolean>>,
    onSelectSuggestion: (String) -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            suggestions.forEach { (title, url, isBm) ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSuggestion(url) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isBm) "★" else "🕒",
                        fontSize = 14.sp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
