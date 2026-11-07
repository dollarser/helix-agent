package com.helix.core.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.file.Files

/** Exercises the packaged JNI entry point using only a private, disposable test directory. */
@RunWith(AndroidJUnit4::class)
class WorkspaceNativeIdentityDeviceTest {
    @Test
    fun identitySurvivesContentChangesButRejectsLinksAndDistinguishesAllocations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "workspace-native-")
        try {
            val first = Files.createDirectory(root.resolve("first"))
            val second = Files.createDirectory(root.resolve("second"))
            val original = WorkspaceNativeIdentity.witness(first)
            Files.write(first.resolve("owned.txt"), "test-owned".toByteArray())
            assertEquals(original, WorkspaceNativeIdentity.witness(first))
            assertNotEquals(original, WorkspaceNativeIdentity.witness(second))
            val link = Files.createSymbolicLink(root.resolve("link"), first)
            try {
                assertThrows(IOException::class.java) { WorkspaceNativeIdentity.witness(link) }
            } finally {
                Files.delete(link)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
