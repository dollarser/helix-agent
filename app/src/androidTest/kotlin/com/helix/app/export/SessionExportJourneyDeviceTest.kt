package com.helix.app.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room -> prepared JSONL -> granted system document; no model/provider account is configured. */
class SessionExportJourneyDeviceTest {
    @Test fun cancellationWhileOpeningTargetRemainsCancellationAndCleansTheNewFile() =
        runBlocking {
            SessionExportFixture().use { fixture ->
                fixture.service.cleanupInterrupted()
                fixture.seed()
                val target = fixture.create("slow-open")
                val export = async { fixture.service.export("selected", target) }
                withTimeout(15000) { while (fixture.opens(target) == 0) delay(25) }
                assertTrue(export.isActive)
                export.cancel()
                withTimeout(5000) { export.join() }
                assertFalse(fixture.exists(target))
                assertTrue(fixture.temporaryIsEmpty())
                assertEquals(
                    1,
                    fixture.storage.messages
                        .listBySession("selected")
                        .size,
                )
            }
        }

    @Test fun writesAndClosesActualDocumentWithCompleteTailAndStableMessageIdentity() =
        runBlocking {
            SessionExportFixture().use { fixture ->
                fixture.service.cleanupInterrupted()
                fixture.seed()
                val target = fixture.create("normal")
                fixture.service.export("selected", target)
                assertEquals(
                    "Normal completion must not send cancellation to the provider",
                    0,
                    fixture.cancellations(target),
                )
                val rows =
                    fixture
                        .read(target)
                        .lineSequence()
                        .filter(String::isNotEmpty)
                        .map {
                            Json.parseToJsonElement(it).jsonObject
                        }.toList()
                assertEquals("\"header\"", rows.first()["type"].toString())
                assertEquals("\"complete\"", rows.last()["type"].toString())
                assertEquals(
                    rows.size.toString(),
                    rows
                        .last()
                        .getValue("data")
                        .jsonObject["recordCount"]
                        .toString(),
                )
                assertTrue(rows.any { it["recordId"].toString() == "\"message:message-0\"" })
                assertTrue(fixture.temporaryIsEmpty())
                assertFalse(fixture.service.cleanupInterrupted().interrupted)
            }
        }

    @Test fun revokedWriteFailureAndRealEnospcRejectSuccessAndCleanOnlyCreatedTarget() =
        runBlocking {
            SessionExportFixture().use { fixture ->
                fixture.service.cleanupInterrupted()
                fixture.seed()
                val unrelated = fixture.create("normal")
                for (kind in listOf("denied", "write-error", "no-space")) {
                    val target = fixture.create(kind)
                    var failed = false
                    try {
                        fixture.service.export("selected", target)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failed = true
                        if (kind == "no-space") {
                            if (!failure.toString().contains(
                                    "ENOSPC",
                                )
                            ) {
                                throw AssertionError("Must exercise actual ENOSPC", failure)
                            }
                        }
                    }
                    assertTrue("$kind must fail", failed)
                    assertFalse("$kind target must be removed", fixture.exists(target))
                    assertTrue(fixture.exists(unrelated))
                    assertTrue(fixture.temporaryIsEmpty())
                    assertEquals(
                        1,
                        fixture.storage.messages
                            .listBySession("selected")
                            .size,
                    )
                }
            }
        }

    @Test fun peerErrorObservedAtCloseCannotBecomeSuccessfulExport() =
        runBlocking {
            SessionExportFixture().use { fixture ->
                fixture.service.cleanupInterrupted()
                fixture.seed()
                val target = fixture.create("close-error")
                var failed = false
                var written = 0L
                try {
                    fixture.service.export("selected", target) {
                        written = it
                        fixture.failClose(target)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                }
                assertTrue("Bytes must have reached the descriptor before its close check", written > 0)
                assertTrue(failed)
                assertFalse(fixture.exists(target))
                assertTrue(fixture.temporaryIsEmpty())
            }
        }

    @Test fun cancellationClosesBlockedPipeAndLeavesOriginalSessionIntact() =
        runBlocking {
            SessionExportFixture().use { fixture ->
                fixture.service.cleanupInterrupted()
                fixture.seed(128)
                val target = fixture.create("slow")
                val export = async { fixture.service.export("selected", target) }
                withTimeout(15000) { while (fixture.opens(target) == 0) delay(25) }
                delay(250) // The provider intentionally never reads; the export is larger than pipe capacity.
                assertTrue(export.isActive)
                export.cancel()
                withTimeout(5000) { export.join() }
                assertFalse(fixture.exists(target))
                assertTrue(fixture.temporaryIsEmpty())
                assertEquals(
                    128,
                    fixture.storage.messages
                        .listBySession("selected")
                        .size,
                )
            }
        }
}
