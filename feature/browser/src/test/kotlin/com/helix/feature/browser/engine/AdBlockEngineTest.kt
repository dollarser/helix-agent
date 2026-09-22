package com.helix.feature.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockEngineTest {
    @Test
    fun shouldBlock_knownAdDomains() {
        val engine = AdBlockEngine(initialEnabled = true)
        assertTrue(engine.shouldBlock("https://googleads.g.doubleclick.net/pagead/ads?client=ca-pub"))
        assertTrue(engine.shouldBlock("https://pos.baidu.com/auto_render"))
        assertTrue(engine.shouldBlock("https://cpro.baidustatic.com/cpro/ui/c.js"))
        assertTrue(engine.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(engine.shouldBlock("https://adnxs.com/seg?add=1"))
    }

    @Test
    fun shouldBlock_pathPatterns() {
        val engine = AdBlockEngine(initialEnabled = true)
        assertTrue(engine.shouldBlock("https://example.com/pagead/ad_unit.js"))
        assertTrue(engine.shouldBlock("https://news.site.com/adservice/tracker"))
    }

    @Test
    fun shouldAllow_normalUrls() {
        val engine = AdBlockEngine(initialEnabled = true)
        assertFalse(engine.shouldBlock("https://github.com/tuyafeng/Via"))
        assertFalse(engine.shouldBlock("https://www.xbext.com/thanks/"))
        assertFalse(engine.shouldBlock("https://en.wikipedia.org/wiki/Kotlin"))
    }

    @Test
    fun shouldBlock_whenDisabled_allowsEverything() {
        val engine = AdBlockEngine(initialEnabled = false)
        assertFalse(engine.shouldBlock("https://googleads.g.doubleclick.net/pagead/ads?client=ca-pub"))
        assertFalse(engine.shouldBlock("https://pos.baidu.com/auto_render"))
    }

    @Test
    fun blockedCount_incrementsCorrectly() {
        val engine = AdBlockEngine(initialEnabled = true)
        assertEquals(0L, engine.blockedCount.value)
        engine.shouldBlock("https://googleads.g.doubleclick.net/pagead/ads")
        assertEquals(1L, engine.blockedCount.value)
        engine.shouldBlock("https://github.com") // allowed -> count not incremented
        assertEquals(1L, engine.blockedCount.value)
        engine.shouldBlock("https://pos.baidu.com/test")
        assertEquals(2L, engine.blockedCount.value)
    }
}
