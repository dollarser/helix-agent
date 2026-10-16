package com.helix.extensions.skills

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class SkillMetadataConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun contributionReadsAndCompoundPublicationDoNotInvertLocks() {
        val gate = Any()
        val root = temporary.root.toPath()
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                metadataLock = gate,
                contributedDocuments = { synchronized(gate) { emptyList() } },
            )
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publishing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable).apply { isDaemon = true } }
        try {
            val reader =
                executor.submit {
                    repository.withSnapshotReferences {
                        reading.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        assertTrue(Thread.holdsLock(gate))
                        repository.list()
                    }
                }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val publisher =
                executor.submit {
                    publishing.countDown()
                    synchronized(gate) { repository.withSnapshotReferences { repository.list() } }
                }
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { publisher.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            reader.get(5, TimeUnit.SECONDS)
            publisher.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
