package com.helix.app.account

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only production protocol check; never requests email, changes password or logs out. */
@Suppress("SwallowedException") // Production failure bodies/credentials must not enter instrumentation output.
class AccountHandoffLiveDeviceTest {
    @Test fun refreshesExistingUserAndReadsCurrentAccountWithoutReplacingLogin() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("accountHandoffAcceptance") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.helix.agent.developer" && android.os.Build.HARDWARE == "ranchu")
            val account = (context.applicationContext as HelixApplication).account
            account.restore()
            check(account.signedIn.value) { "Existing signed-in account required" }
            val revision = account.revision
            try {
                AccountProfileClient(account).refreshUser()
                AccountProfileClient(account).registrationPolicy()
                assertTrue(requireNotNull(account.user.value).id.isNotBlank())
                assertTrue(requireNotNull(account.noticeAccountKey).isNotBlank())
                val page = AccountNoticesClient(account).list()
                assertEquals(30, page.pageSize)
                assertTrue(page.unread >= 0)
                assertTrue(
                    AccountUsageClient(account)
                        .read()
                        .summary()
                        .quotas.size == 3,
                )
                assertTrue(account.signedIn.value)
                assertEquals(revision, account.revision)
            } catch (failure: Exception) {
                // Do not propagate remote body, identity, credentials or nested exceptions to JUnit.
                throw AssertionError("Read-only account handoff failed: ${failure.javaClass.simpleName}")
            }
        }
}
