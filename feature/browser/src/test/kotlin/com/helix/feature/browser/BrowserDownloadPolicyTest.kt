package com.helix.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadPolicyTest {
    // The suggested name (server Content-Disposition) and the download URL are
    // INDEPENDENT inputs — hostile names contain characters that would not appear in a
    // real http(s) URL, so the name is never interpolated into the URL.
    private fun request(
        name: String,
        url: String = "https://example.com/files/download",
        mimeType: String? = null,
        contentLength: Long = -1L,
    ) = DownloadRequest(url, name, mimeType, contentLength)

    @Test
    fun plainFilesAreSavedWithTheDeclaredSize() {
        assertEquals(
            DownloadDecision.Save("report.pdf", -1L),
            BrowserDownloadPolicy.evaluate(request("report.pdf")),
        )
        assertEquals(
            DownloadDecision.Save("report.pdf", 1234L),
            BrowserDownloadPolicy.evaluate(request("report.pdf", contentLength = 1234L)),
        )
        assertEquals(
            DownloadDecision.Save("README", -1L),
            BrowserDownloadPolicy.evaluate(request("README")),
        )
    }

    @Test
    fun executableAndInstallableSuffixesAreDenied() {
        for (name in listOf("app.apk", "APP.APK", "payload.dex", "lib.so", "plugin.jar", "split.apks", "native.jni")) {
            assertEquals(
                name,
                DownloadDecision.Denied(DownloadDenial.UNSAFE_TYPE),
                BrowserDownloadPolicy.evaluate(request(name)),
            )
        }
    }

    @Test
    fun androidPayloadMimeTypesAreDeniedEvenWithAHarmlessName() {
        assertEquals(
            DownloadDecision.Denied(DownloadDenial.UNSAFE_TYPE),
            BrowserDownloadPolicy.evaluate(request("update.bin", mimeType = "application/vnd.android.package-archive")),
        )
        assertEquals(
            DownloadDecision.Denied(DownloadDenial.UNSAFE_TYPE),
            BrowserDownloadPolicy.evaluate(request("payload", mimeType = "application/x-dex")),
        )
    }

    @Test
    fun onlyHttpAndHttpsDownloadUrlsAreAllowed() {
        for (url in listOf("data:text/html,x", "file:///etc/passwd", "https:", "not a url")) {
            assertEquals(
                url,
                DownloadDecision.Denied(DownloadDenial.URL),
                BrowserDownloadPolicy.evaluate(request("x.txt", url = url)),
            )
        }
    }

    @Test
    fun declaredSizeOverTheCapIsDeniedAtTheExactBoundary() {
        val cap = BrowserDownloadPolicy.MAX_DOWNLOAD_BYTES
        assertEquals(
            DownloadDecision.Denied(DownloadDenial.SIZE),
            BrowserDownloadPolicy.evaluate(request("big.bin", contentLength = cap + 1)),
        )
        assertTrue(
            BrowserDownloadPolicy.evaluate(request("big.bin", contentLength = cap)) is DownloadDecision.Save,
        )
    }

    @Test
    fun directoryPartsAndControlCharsAreStrippedFromTheSuggestedName() {
        assertEquals(
            DownloadDecision.Save("c.txt", -1L),
            BrowserDownloadPolicy.evaluate(request("a/b/c.txt")),
        )
        assertEquals(
            DownloadDecision.Save("notes.txt", -1L),
            BrowserDownloadPolicy.evaluate(request("dir\\sub\\notes.txt")),
        )
        assertEquals(
            DownloadDecision.Save("ab.txt", -1L),
            BrowserDownloadPolicy.evaluate(request("a\u0000b.txt")),
        )
        assertEquals(
            DownloadDecision.Save("noext", -1L),
            BrowserDownloadPolicy.evaluate(request("  ...noext  ")),
        )
    }

    @Test
    fun aPathOnlySuggestedNameIsDeniedAsUnsavable() {
        assertEquals(
            DownloadDecision.Denied(DownloadDenial.NAME),
            BrowserDownloadPolicy.evaluate(request("..\\..\\..")),
        )
        assertEquals(
            DownloadDecision.Denied(DownloadDenial.NAME),
            BrowserDownloadPolicy.evaluate(request("...")),
        )
    }

    @Test
    fun overlongNamesAreTruncatedToTheCap() {
        val longName = "a".repeat(200) + ".txt"
        val decision = BrowserDownloadPolicy.evaluate(request(longName))
        val save = decision as DownloadDecision.Save
        assertEquals(BrowserDownloadPolicy.MAX_NAME_LENGTH, save.targetName.length)
    }

    @Test
    fun aPathLeadingExecutableSuffixIsStillCaughtAfterStripping() {
        assertEquals(
            DownloadDecision.Denied(DownloadDenial.UNSAFE_TYPE),
            BrowserDownloadPolicy.evaluate(request("C:\\Windows\\System32\\evil.apk")),
        )
    }
}
