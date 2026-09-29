package com.helix.core.storage.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ContentPublicationRaceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cleanupCheckCannotDeleteAReferencePublishedByAConcurrentReuse() {
        val root = tmp.newFolder()
        val first = FileContentStore(root)
        val second = FileContentStore(File(root, "."))
        val ref = first.write("same body")
        File(root, ref.relativePath).setLastModified(1)
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val referenced = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val cleaner =
            thread {
                try {
                    StorageGarbageCollector.collectGarbage(root, {
                        checked.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        referenced.get()
                    }, 0)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        val publisher =
            thread {
                entered.countDown()
                try {
                    second.withPublication {
                        second.write("same body")
                        referenced.set(true)
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (publisher.state != Thread.State.BLOCKED && publisher.isAlive && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, publisher.state)
        } finally {
            release.countDown()
            cleaner.join(5000)
            publisher.join(5000)
        }
        failure.get()?.let { throw AssertionError(it) }
        assertTrue(referenced.get())
        assertEquals("same body", second.read(ref))
    }

    @Test fun reusedOldBodyIsProtectedUntilReferenceCommitAndRollbackLeavesCollectableOrphan() {
        val root = tmp.newFolder()
        val store = FileContentStore(root)
        val ref = store.write("old body")
        File(root, ref.relativePath).setLastModified(1)
        val referenced = AtomicBoolean(false)
        val started = CountDownLatch(1)
        lateinit var cleaner: Thread
        store.withPublication {
            store.write("old body")
            cleaner =
                thread {
                    started.countDown()
                    StorageGarbageCollector.collectGarbage(root, { referenced.get() }, 0)
                }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            referenced.set(true)
        }
        cleaner.join(5000)
        assertEquals("old body", store.read(ref))
        referenced.set(false)
        assertEquals(1, StorageGarbageCollector.collectGarbage(root, { referenced.get() }, 0).deletedContentFiles)
    }
}
