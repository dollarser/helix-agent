package com.helix.app.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLaunchGateTest {
    @Test fun preparationFailureIncludingHookCancellationIsNotUserStop() =
        runBlocking {
            listOf(IllegalStateException("database"), CancellationException("hook")).forEach { failure ->
                supervisorScope {
                    val gate = TurnLaunchGate()
                    var ran = false
                    val waiting =
                        async {
                            gate.await()
                            ran = true
                        }
                    gate.prepare { throw failure }
                    val caught = runCatching { waiting.await() }.exceptionOrNull()
                    assertFalse(caught is CancellationException)
                    assertSame(failure, caught?.cause)
                    assertFalse(ran)
                }
            }
        }

    @Test fun successfulPreparationOpensTheDriver() =
        runBlocking {
            val gate = TurnLaunchGate()
            var prepared = false
            gate.prepare { prepared = true }
            gate.await()
            assertTrue(prepared)
        }
}
