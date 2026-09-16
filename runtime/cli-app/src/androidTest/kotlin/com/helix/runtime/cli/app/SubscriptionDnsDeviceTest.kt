package com.helix.runtime.cli.app

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit opt-in: uses only a manually configured mapping and an unauthenticated GET. */
class SubscriptionDnsDeviceTest {
    @Test fun manualMappingConnectsWithoutSystemHostsOrCredentials() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("helixDnsProbe") == "true",
        )
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SubscriptionRuntimeEnvironment.initialize(target)
        assertTrue(android.os.Process.myUid() != 0)
        assertNotNull(settings.lookup("chatgpt.com"))
        assertFalse(File("/system/etc/hosts").readText().contains("chatgpt.com"))
        val client =
            OkHttpClient
                .Builder()
                .dns(BoundedDnsCache())
                .connectTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        try {
            client
                .newCall(Request.Builder().url("https://chatgpt.com/backend-api/codex/models").build())
                .execute()
                .use { response ->
                    assertEquals(401, response.code)
                    assertNotNull(response.handshake)
                }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
