package com.helix.extensions.mcp

import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class McpResponseWitnessTest {
    @Test
    fun onlyMatchingWireErrorIsEvidenceRegardlessOfErrorCode() =
        runBlocking {
            val witness = McpResponseWitness()
            val failure = IOException("local transport text -32602")
            for (code in listOf(-32602, 0, 12345)) {
                val result =
                    runCatching {
                        witness.observe("tools/call") {
                            witness.sent(JSONRPCRequest(7, "tools/call"), it)
                            witness.received(error(7, code))
                            throw failure
                        }
                    }.exceptionOrNull()
                assertTrue(result is McpProtocolResponseException)
                assertEquals(code, (result as McpProtocolResponseException).code)
                assertSame(failure, result.cause)
                assertFalse(result.message!!.contains("server-secret"))
            }
        }

    @Test
    fun unrelatedUnsolicitedLateAndDuplicateResponsesCannotBecomeEvidence() =
        runBlocking {
            val witness = McpResponseWitness()
            val failure = IOException("JSON-RPC -32602")
            witness.received(error(7, -32602))
            for (mode in listOf("none", "wrong-id", "wrong-method", "success-first", "string-id")) {
                val result =
                    runCatching {
                        witness.observe("tools/call") {
                            witness.sent(JSONRPCRequest(7, if (mode == "wrong-method") "ping" else "tools/call"), it)
                            when (mode) {
                                "wrong-id" -> {
                                    witness.received(error(8, -32602))
                                }

                                "wrong-method" -> {
                                    witness.received(error(7, -32602))
                                }

                                "success-first" -> {
                                    witness.received(JSONRPCResponse(RequestId.NumberId(7), EmptyResult()))
                                    witness.received(error(7, -32602))
                                }

                                "string-id" -> {
                                    witness.received(JSONRPCError(RequestId.StringId("7"), RPCError(-32602, "error")))
                                }
                            }
                            throw failure
                        }
                    }.exceptionOrNull()
                assertSame(mode, failure, result)
            }
            witness.received(error(7, -32602))
            val next =
                runCatching {
                    witness.observe("tools/call") {
                        witness.sent(JSONRPCRequest(8, "tools/call"), it)
                        witness.received(error(7, -32602))
                        throw failure
                    }
                }.exceptionOrNull()
            assertSame(failure, next)
        }

    @Test
    fun cancellationAndInterruptionKeepTheirIdentityEvenAfterErrorReply() =
        runBlocking {
            val witness = McpResponseWitness()
            for (failure in listOf(CancellationException("cancelled"), InterruptedException("interrupted"))) {
                val result =
                    runCatching {
                        witness.observe("resources/list") {
                            witness.sent(JSONRPCRequest(7, "resources/list"), it)
                            witness.received(error(7, -32602))
                            throw failure
                        }
                    }.exceptionOrNull()
                assertSame(failure, result)
            }
        }

    @Test(timeout = 5000)
    fun concurrentSameMethodRequestsKeepIndependentOutOfOrderEvidence() =
        runBlocking {
            val witness = McpResponseWitness()
            val firstSent = CompletableDeferred<Unit>()
            val secondSent = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val firstFailure = IOException("first transport failure")
            val first =
                async {
                    runCatching {
                        witness.observe("tools/call") {
                            witness.sent(JSONRPCRequest(1, "tools/call"), it)
                            firstSent.complete(Unit)
                            releaseFirst.await()
                            throw firstFailure
                        }
                    }.exceptionOrNull()
                }
            firstSent.await()
            val second =
                async {
                    runCatching {
                        witness.observe("tools/call") {
                            witness.sent(JSONRPCRequest(2, "tools/call"), it)
                            secondSent.complete(Unit)
                            witness.received(error(2, -32602))
                            throw IOException("second SDK failure")
                        }
                    }.exceptionOrNull()
                }
            secondSent.await()
            assertTrue(second.await() is McpProtocolResponseException)
            releaseFirst.complete(Unit)
            assertSame(firstFailure, first.await())
        }

    private fun error(
        id: Long,
        code: Int,
    ) = JSONRPCError(RequestId.NumberId(id), RPCError(code, "server-secret"))
}
