package com.helix.app.marketplace

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class MarketplaceProcessRecoveryDeviceTest {
    @Test
    @Suppress("LongMethod") // The owned runner executes setup death and verify in different real processes.
    fun committedCatalogAndInstallationSurviveCandidateProcessDeath() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        val root = app.noBackupFilesDir.toPath().resolve("hxa282-market-recovery")
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase") ?: "verify"
        val store = MarketplaceSourceStore(root.resolve("sources"))
        val service = MarketplaceService(c.pluginService, c.skillRepository, store)
        when (phase) {
            "setup" -> {
                require(!Files.exists(root)) { "Owned recovery fixture already exists" }
                Files.createDirectories(root)
                val name = "market-recovery-${UUID.randomUUID().toString().take(8)}"
                val first = service.imports.previewSource(writeMarketplaceFixture(root, name, "1.0"))
                service.imports.addSource(first)
                val item = service.items().single { it.imported?.entry?.name == name }
                val installed = service.imports.installCandidate(item, service.imports.previewItem(item), null)
                Files.writeString(
                    root.resolve("facts"),
                    listOf(name, first.catalog.contentHash, installed.id, installed.hash).joinToString("\n"),
                )
                Files.writeString(
                    app.noBackupFilesDir.toPath().resolve("recovery-device-pid"),
                    Process.myPid().toString(),
                )
                val second = service.imports.previewSource(writeMarketplaceFixture(root, name, "2.0"))
                service.imports.addSource(second) {
                    val staged =
                        Files.list(root.resolve("sources")).use { paths ->
                            paths.anyMatch { it.fileName.toString().startsWith(".pending-") }
                        }
                    if (staged) Process.killProcess(Process.myPid())
                    false
                }
                error("Setup did not die before candidate publication")
            }

            "verify" -> {
                val facts = Files.readAllLines(root.resolve("facts"))
                require(facts.size == 4)
                try {
                    val oldPid =
                        Files
                            .readString(
                                app.noBackupFilesDir.toPath().resolve("recovery-device-pid"),
                            ).trim()
                            .toInt()
                    assertNotEquals(oldPid, Process.myPid())
                    val source = service.imports.sources().single()
                    assertEquals(facts[1], source.contentHash)
                    val item = service.items().single { it.imported?.entry?.name == facts[0] }
                    assertEquals("1.0", service.imports.previewItem(item).versionLabel)
                    val installed = requireNotNull(service.findInstalled(item))
                    assertEquals(facts[2], installed.id)
                    assertEquals(facts[3], installed.hash)
                    assertEquals("1.0", installed.versionLabel)
                    assertTrue(
                        Files.list(root.resolve("sources")).use { paths ->
                            paths.anyMatch { it.fileName.toString().startsWith(".pending-") }
                        },
                    )
                    println("MARKET_RECOVERY=PASS processChanged=true stagedCandidateIgnored=true")
                } finally {
                    c.pluginService
                        .list()
                        .filter { it.id == facts[2] }
                        .forEach(c.pluginService::remove)
                    removeMarketplaceFixture(root)
                    Files.deleteIfExists(app.noBackupFilesDir.toPath().resolve("recovery-device-pid"))
                }
            }

            else -> {
                error("Invalid recoveryPhase")
            }
        }
    }
}
