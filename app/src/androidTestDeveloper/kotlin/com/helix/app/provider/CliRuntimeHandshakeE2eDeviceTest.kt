package com.helix.app.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.cli.client.CliRuntimeProtocol
import com.helix.runtime.cli.client.CliRuntimeSupervisor
import com.helix.runtime.cli.client.CliRuntimeVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliRuntimeHandshakeE2eDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun explicitColdBindOrExpectedLocalRefusalIsStable() {
        val result = CliRuntimeSupervisor(context).verify()
        val expectedCause = InstrumentationRegistry.getArguments().getString("cliRuntimeExpectedCause")
        if (expectedCause != null) {
            assertTrue(result is CliRuntimeVerification.Unavailable)
            assertEquals(expectedCause, (result as CliRuntimeVerification.Unavailable).cause.name)
            return
        }
        assertTrue(result is CliRuntimeVerification.Verified)
        val status = (result as CliRuntimeVerification.Verified).status
        assertEquals(CliRuntimeProtocol.VERSION, status.protocolVersion)
        assertEquals("arm64-v8a", status.abi)
        assertEquals("NOT_REGISTERED", status.agentBackendState)
    }
}
