package com.helix.app.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProbeGateTest {
    @Test fun independentProbeKindsDoNotSupersedeEachOtherButEditsInvalidateAll() =
        runBlocking {
            val gate = ProviderProbeGate()
            val connection = gate.begin("p", "connection") { "A" }.first
            val capabilities = gate.begin("p", "capabilities") { "A" }.first
            val context = gate.begin("p", "context:model") { "A" }.first
            assertTrue(gate.publish("p", connection) {})
            assertTrue(gate.publish("p", capabilities) {})
            assertTrue(gate.publish("p", context) {})
            gate.mutate("p") {}
            listOf(connection, capabilities, context).forEach { token ->
                assertFalse(gate.publish("p", token) { error("old configuration survived edit") })
            }
        }

    @Test fun editInvalidatesAnAlreadyReturnedButNotPublishedSuccess() =
        runBlocking {
            val gate = ProviderProbeGate()
            var configuration = "endpoint-A/key-1"
            var status = "UNTESTED"
            val returned = CompletableDeferred<Unit>()
            val publish = CompletableDeferred<Unit>()
            val old =
                async(start = CoroutineStart.UNDISPATCHED) {
                    val (token, snapshot) = gate.begin("p") { configuration }
                    returned.complete(Unit)
                    publish.await()
                    gate.publish("p", token) { status = "PASSED:$snapshot" }
                }
            returned.await()
            gate.mutate("p") {
                configuration = "endpoint-B/key-2"
                status = "UNTESTED"
            }
            publish.complete(Unit)
            assertFalse(old.await())
            assertEquals("UNTESTED", status)
        }

    @Test fun olderFailureCannotReplaceANewerSuccessAndOtherProvidersAreIndependent() =
        runBlocking {
            val gate = ProviderProbeGate()
            var status = "UNTESTED"
            val old = gate.begin("p") { "A" }.first
            val unrelated = gate.begin("other") { "B" }.first
            val fresh = gate.begin("p") { "A" }.first
            assertTrue(gate.publish("p", fresh) { status = "PASSED" })
            assertFalse(gate.publish("p", old) { status = "FAILED" })
            assertTrue(gate.publish("other", unrelated) {})
            assertEquals("PASSED", status)
        }

    @Test fun deletionAndSameConfigurationRecreationDoNotResurrectTheOldProbe() =
        runBlocking {
            val gate = ProviderProbeGate()
            val old = gate.begin("p") { "A" }.first
            gate.mutate("p") {}
            assertFalse(gate.publish("p", old) { error("deleted result published") })
            gate.begin("p") { "A" }
            assertFalse(gate.publish("p", old) { error("old lifetime result published") })
        }

    @Test fun cancelledCallerCannotPublishEvenIfNetworkReturnedWithoutSuspension() =
        runBlocking {
            val gate = ProviderProbeGate()
            var published = false
            val task =
                async {
                    val token = gate.begin("p") { "A" }.first
                    coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                    gate.publish("p", token) { published = true }
                }
            task.join()
            assertFalse(published)
        }

    @Test fun cancellationBeforePublicationDoesNotWriteAResult() =
        runBlocking {
            val gate = ProviderProbeGate()
            val ready = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var published = false
            val task =
                async(start = CoroutineStart.UNDISPATCHED) {
                    val token = gate.begin("p") { "A" }.first
                    ready.complete(Unit)
                    release.await()
                    gate.publish("p", token) { published = true }
                }
            ready.await()
            task.cancel()
            task.join()
            assertFalse(published)
        }
}
