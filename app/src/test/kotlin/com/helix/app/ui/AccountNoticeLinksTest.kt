package com.helix.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountNoticeLinksTest {
    @Test fun recognizesOnlyExplicitWebLinksWithoutChangingPlainText() {
        val body =
            "See HTTPS://example.test/a?q=1&b=2。 " +
                "ftp://x.test javascript:alert(1) www.x.test https://two.test."
        val links = accountNoticeLinks(body)
        assertEquals(listOf("HTTPS://example.test/a?q=1&b=2", "https://two.test"), links.map { it.url })
        links.forEach { assertEquals(it.url, body.substring(it.start, it.end)) }
    }

    @Test fun acceptsInternationalHostsWithoutRewritingCopiedAddress() {
        val url = "https://例子.测试/页面?q=一"
        assertEquals(url, accountNoticeLinks(url).single().url)
    }

    @Test fun chineseAdjacentProseAndPunctuationDoNotHideOrExtendBareLinks() {
        val body = "查看https://example.test，获取详情；访问http://second.test详情"
        assertEquals(listOf("https://example.test", "http://second.test"), accountNoticeLinks(body).map { it.url })
    }

    @Test fun retainsBalancedUrlParenthesesButNotSurroundingPunctuation() {
        val body = "(https://example.test/a_(b)). [http://other.test/page]"
        assertEquals(
            listOf("https://example.test/a_(b)", "http://other.test/page"),
            accountNoticeLinks(body).map { it.url },
        )
    }

    @Test fun rejectsMalformedOrEmbeddedSchemesAndNeverParsesHtml() {
        assertTrue(
            accountNoticeLinks("https:// javascript:http://example.test file:///a intent://x https:///bad").isEmpty(),
        )
        val html = "<b>Hello</b> <a href=\"https://example.test\">literal</a>"
        val only = accountNoticeLinks(html).single()
        assertEquals("https://example.test", html.substring(only.start, only.end))
    }
}
