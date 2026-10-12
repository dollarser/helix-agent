package com.helix.app.marketplace

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.plugin.PluginPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName")
internal fun itemName(item: MarketplaceItem): String = item.imported?.entry?.name ?: stringResource(item.nameRes)

@Composable
@Suppress("FunctionName")
internal fun itemSummary(item: MarketplaceItem): String =
    item.imported?.entry?.let { entry ->
        when {
            entry.issue != null -> stringResource(R.string.plugin_market_package_needed)
            entry.description.isNotBlank() -> entry.description
            else -> stringResource(R.string.plugin_market_portable)
        }
    } ?: stringResource(item.summaryRes)

internal fun canInstall(item: MarketplaceItem): Boolean =
    item.imported
        ?.entry
        ?.bundle
        ?.let(::hasSupportedComponents) ?: true

internal fun hasSupportedComponents(bundle: PluginPackage): Boolean =
    bundle.skills.isNotEmpty() || bundle.endpoints.isNotEmpty()

@Composable
@Suppress("FunctionName")
internal fun MarketplaceRefresh(
    service: MarketplaceService,
    revision: Int,
    onLoaded: (Pair<List<MarketplaceItem>, Map<String, MarketplaceItemStatus>>?) -> Unit,
) {
    LaunchedEffect(service, revision) {
        try {
            val loaded =
                withContext(Dispatchers.IO) {
                    val items = service.items()
                    items to items.associate { it.id to service.status(it) }
                }
            onLoaded(loaded)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            onLoaded(null)
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun MarketplaceLoadFailure(
    failed: Boolean,
    onRetry: () -> Unit,
) {
    if (failed) {
        Text(stringResource(R.string.plugin_market_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.plugin_market_retry)) }
    }
}

@Composable
@Suppress("FunctionName")
internal fun MarketplaceCandidatePresentation(
    service: MarketplaceService,
    item: MarketplaceItem?,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    item?.let { MarketplaceCandidateDialog(service, it, onDismiss, onInstalled) }
}

internal fun requestMarketplaceInstall(
    item: MarketplaceItem,
    onPreview: (MarketplaceItem) -> Unit,
    onBuiltin: () -> Unit,
) {
    if (item.imported == null) onBuiltin() else onPreview(item)
}
