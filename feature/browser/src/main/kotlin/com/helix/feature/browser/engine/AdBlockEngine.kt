package com.helix.feature.browser.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance ad blocking and content filtering engine inspired by Via and X Browser.
 *
 * Intercepts ad, tracker, and telemetry network requests via O(1) domain lookup and path patterns,
 * and supplies cosmetic CSS for element hiding.
 */
class AdBlockEngine(
    initialEnabled: Boolean = true,
) {
    var enabled: Boolean = initialEnabled

    private val _blockedCount = MutableStateFlow(0L)
    val blockedCount: StateFlow<Long> = _blockedCount.asStateFlow()

    fun resetCount() {
        _blockedCount.value = 0L
    }

    /**
     * Determines whether [url] should be blocked.
     */
    fun shouldBlock(url: String): Boolean {
        if (!enabled) return false

        val uri =
            try {
                URI(url)
            } catch (
                @Suppress("SwallowedException") e: URISyntaxException,
            ) {
                null
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                null
            }

        val host = uri?.host?.lowercase()
        val path = uri?.path?.lowercase()

        val blocked = (host != null && isBlockedHost(host)) || (path != null && hasBlockedPath(path))
        if (blocked) {
            _blockedCount.value += 1
        }
        return blocked
    }

    private fun isBlockedHost(host: String): Boolean {
        if (BLOCKED_DOMAINS.contains(host)) return true

        var dotIndex = host.indexOf('.')
        var blocked = false
        while (dotIndex != -1 && dotIndex < host.length - 1) {
            val parent = host.substring(dotIndex + 1)
            if (BLOCKED_DOMAINS.contains(parent)) {
                blocked = true
                break
            }
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return blocked
    }

    private fun hasBlockedPath(path: String): Boolean {
        for (pattern in BLOCKED_PATHS) {
            if (path.contains(pattern)) return true
        }
        return false
    }

    companion object {
        /**
         * Common ad and tracking domains (both global and CN).
         */
        val BLOCKED_DOMAINS: Set<String> =
            setOf(
                // Google Ads & Analytics
                "doubleclick.net",
                "googleadservices.com",
                "googlesyndication.com",
                "google-analytics.com",
                "pagead2.googlesyndication.com",
                "adservice.google.com",
                "adservice.google.cn",
                "app-measurement.com",
                // Baidu & CN Ad Networks
                "cpro.baidustatic.com",
                "pos.baidu.com",
                "mobads.baidu.com",
                "mobads-logs.baidu.com",
                "e.qq.com",
                "mi.gdt.qq.com",
                "t.gdt.qq.com",
                "pgdt.gtimg.cn",
                "ad.toutiao.com",
                "pangolin.snssdk.com",
                "log.snssdk.com",
                "adash.m.taobao.com",
                "adash.man.aliyuncs.com",
                "sax.sina.com.cn",
                "ads.union.jd.com",
                "stat.m.jd.com",
                // Global Ad & Tracking Networks
                "adnxs.com",
                "rubiconproject.com",
                "scorecardresearch.com",
                "unityads.unity3d.com",
                "applovin.com",
                "ironsrc.mobi",
                "vungle.com",
                "chartboost.com",
                "taboola.com",
                "outbrain.com",
                "moatads.com",
                "crwdcntrl.net",
                "pubmatic.com",
                "openx.net",
                "advertising.com",
                "quantserve.com",
                "inmobi.com",
                "flurry.com",
                "adjust.com",
                "appsflyer.com",
            )

        private val BLOCKED_PATHS: List<String> =
            listOf(
                "/pagead/",
                "/adservice/",
                "/adserver/",
                "/ads/banner/",
                "/mobads/",
            )

        /**
         * CSS injected into page to hide typical ad elements.
         */
        val COSMETIC_CSS: String =
            """
            [id*="google_ads"],
            [id*="ad-banner"],
            [class*="ad-container"],
            [class*="advertisement"],
            [class*="adsbygoogle"],
            .ad-wrapper,
            .ad_wrapper,
            .banner-ad,
            #ad-header,
            #ad-footer {
                display: none !important;
                visibility: hidden !important;
                height: 0 !important;
            }
            """.trimIndent()
    }
}
