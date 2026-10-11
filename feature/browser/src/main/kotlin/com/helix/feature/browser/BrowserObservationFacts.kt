package com.helix.feature.browser

import com.helix.core.policy.BrowserTabScope
import com.helix.feature.browser.snapshot.BrowserSnapshot
import com.helix.feature.browser.webview.WebViewTabHost
import com.helix.tools.browser.BrowserFindMatch
import com.helix.tools.browser.BrowserObservationContext
import com.helix.tools.browser.BrowserWaitStatus
import com.helix.tools.browser.FindOutcome

internal object BrowserObservationFacts {
    fun prefix(
        value: String,
        limit: Int,
    ): String =
        value.take(limit).let {
            if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
        }

    fun allowed(
        tab: BrowserTab,
        context: BrowserObservationContext,
    ): Boolean =
        if (context.scopeRef != null) {
            tab.navigationGeneration in 0..Int.MAX_VALUE.toLong() &&
                BrowserTabScope(tab.id, tab.navigationGeneration.toInt()).toScopeRef() == context.scopeRef
        } else {
            context.sessionId != null && tab.originSessionId == context.sessionId
        }

    fun find(
        snapshot: BrowserSnapshot,
        query: String,
        expired: Boolean,
    ): FindOutcome {
        val needle = query.lowercase()
        val matching =
            snapshot.nodes.filter { node ->
                needle.isNotEmpty() &&
                    listOf(node.text.text, node.name?.text.orEmpty(), node.role, node.href?.text.orEmpty())
                        .joinToString(" ")
                        .lowercase()
                        .contains(needle)
            }
        return FindOutcome(
            true,
            snapshot.tabId,
            query,
            matching.size,
            matching.take(50).map { BrowserFindMatch(it.index, it.role, it.text.text, it.token) },
            "",
            snapshot.truncated || matching.size > 50,
            expired,
            snapshot.mintedAtMillis,
        )
    }
}

/** Captured real references for one observation; no additional mutable owner registry. */
internal data class BrowserObservationBinding(
    val tabId: String,
    val owner: BrowserViewOwner,
    val host: WebViewTabHost,
    val generation: Long,
) {
    @Suppress("ReturnCount") // host and document loss are different terminal facts
    fun state(
        currentOwner: BrowserViewOwner?,
        currentHost: WebViewTabHost?,
        tab: BrowserTab?,
    ): BrowserWaitStatus? {
        if (currentOwner !== owner || currentHost !== host ||
            !owner.available
        ) {
            return BrowserWaitStatus.HOST_UNAVAILABLE
        }
        if (tab == null) return BrowserWaitStatus.PAGE_CHANGED
        return if (tab.isLoading || tab.error != null ||
            tab.navigationGeneration != generation
        ) {
            BrowserWaitStatus.PAGE_CHANGED
        } else {
            null
        }
    }

    companion object {
        @Suppress("ReturnCount") // only a settled real owner/host/document may be bound
        fun capture(
            tab: BrowserTab?,
            owner: BrowserViewOwner?,
            host: WebViewTabHost?,
        ): BrowserObservationBinding? {
            if (tab == null || owner == null || host == null) return null
            if (!owner.available || tab.isLoading || tab.error != null) return null
            if (tab.navigationGeneration < 1) return null
            return BrowserObservationBinding(tab.id, owner, host, tab.navigationGeneration)
        }
    }
}
