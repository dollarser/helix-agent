package com.helix.core.storage.export

import com.helix.core.storage.content.FileContentStore
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class SessionExportContentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun inlineBoundaryAndLargeReferenceDoNotConfuseAvailabilityWithVerification() {
        val store = FileContentStore(temporary.root)
        val resolver = SessionExportContent(store, { it }, {})
        val small = store.write("x".repeat(SessionExportFormat.INLINE_BYTES))
        val large = store.write("x".repeat(SessionExportFormat.INLINE_BYTES + 1))
        val inline = resolver.describe(small)
        assertEquals("inline", inline.getValue("availability").jsonPrimitive.content)
        assertEquals(small.sha256, inline.getValue("exportedSha256").jsonPrimitive.content)
        val reference = resolver.describe(large)
        assertEquals("reference_only", reference.getValue("availability").jsonPrimitive.content)
        assertEquals("not_read", reference.getValue("verification").jsonPrimitive.content)
        assertFalse(reference.containsKey("text"))
        assertFalse(reference.toString().contains(temporary.root.absolutePath))
    }

    @Test
    fun missingAndReplacedBodiesAreVisibleWithoutExportingUnverifiedText() {
        val store = FileContentStore(temporary.root)
        val resolver = SessionExportContent(store, { it }, {})
        val ref = store.write("original")
        File(temporary.root, ref.relativePath).writeText("replaced")
        val changed = resolver.describe(ref)
        assertEquals("changed", changed.getValue("availability").jsonPrimitive.content)
        assertFalse(changed.containsKey("text"))
        store.delete(ref)
        assertEquals(
            "missing",
            resolver
                .describe(ref)
                .getValue("availability")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun transformedTextKeepsOriginalAndExportedHashesSeparate() {
        val store = FileContentStore(temporary.root)
        val ref = store.write("synthetic credential")
        val result = SessionExportContent(store, { "[redacted]" }, {}).describe(ref)
        assertEquals("redacted", result.getValue("availability").jsonPrimitive.content)
        assertEquals(ref.sha256, result.getValue("sourceSha256").jsonPrimitive.content)
        assertNotEquals(ref.sha256, result.getValue("exportedSha256").jsonPrimitive.content)
        val limited =
            SessionExportContent(
                store,
                { "x".repeat(SessionExportFormat.INLINE_BYTES + 1) },
                {},
            ).describe(ref)
        assertEquals("omitted_limit", limited.getValue("availability").jsonPrimitive.content)
        assertFalse(limited.containsKey("text"))
    }

    @Test
    fun cancellationAndSanitizerFailureCannotBecomeSuccessfulContentRecords() {
        val store = FileContentStore(temporary.root)
        val ref = store.write("body")
        assertThrows(CancellationException::class.java) {
            SessionExportContent(store, { it }, { throw CancellationException() }).describe(ref)
        }
        assertThrows(IllegalStateException::class.java) {
            SessionExportContent(store, { error("sanitizer failed") }, {}).describe(ref)
        }
    }
}
