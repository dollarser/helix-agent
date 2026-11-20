package com.helix.app.account

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in read-only production check. Does not acknowledge notices or change the signed-in account. */
class AccountNoticesLiveDeviceTest {
    @Test fun readsCurrentAccountNoticesWithoutChangingReadState() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("accountNoticesAcceptance") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val application = context.applicationContext as HelixApplication
            val account = application.account
            account.restore()
            check(account.signedIn.value) { "Existing signed-in test account required" }
            val client = AccountNoticesClient(account)
            val first = client.list()
            val count = client.unreadCount()
            val unread = client.list(state = AccountNoticeState.UNREAD)
            assertEquals(1, first.page)
            assertEquals(30, first.pageSize)
            assertTrue(count >= 0)
            assertTrue(unread.items.all { it.readAt == null })
            assertTrue(account.signedIn.value)
            File(application.cacheDir, "account-notices-live-result.txt").writeText(
                "list=passed\ncount=passed\nunread=passed\nitems=${first.items.size}\ntotal=${first.total}\n" +
                    "count=$count\nmarkRead=not-run\nlogin=retained\n",
            )
        }
}
