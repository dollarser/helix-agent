package com.helix.app.account

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.AndroidKeystoreSecretStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Two explicit phases around a host cover-install. Uses only synthetic credentials in a dedicated directory. */
class AccountCoverInstallDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(context.filesDir, "validation/account-cover-install")

    private fun storage() =
        AccountSecretStorage(
            AndroidKeystoreSecretStore.create(
                object : ContextWrapper(context) {
                    override fun getFilesDir() = root
                },
            ),
        )

    @Test fun prepareSyntheticLoginBeforeCoverInstall() =
        runBlocking {
            check(!root.exists()) { "Previous fixture must be reviewed before reuse" }
            storage().save(
                AccountTokens(
                    "synthetic-access",
                    "synthetic-refresh",
                    Long.MAX_VALUE - 1,
                    Long.MAX_VALUE,
                    "cover-install-fixture",
                ),
            )
            val session = AccountSession(storage())
            session.restore()
            assertTrue(session.signedIn.value)
            assertEquals("cover-install-fixture", session.identity)
        }

    @Test fun verifySyntheticLoginAfterCoverInstall() =
        runBlocking {
            check(root.exists()) { "Prepare phase is required" }
            try {
                val session = AccountSession(storage())
                session.restore()
                assertEquals(AccountStatus.SIGNED_IN, session.status.value)
                assertEquals("cover-install-fixture", session.identity)
            } finally {
                storage().clear()
                check(root.deleteRecursively())
            }
        }
}
