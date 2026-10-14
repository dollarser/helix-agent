package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Mobile Use is host-bundled in this channel; repair cannot create user permission. */
class NativePluginRepairDeviceTest {
    @Test fun nativeRepairKeepsSessionChoiceWithoutCreatingPermission() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val service = container.pluginService
            val record = service.list().single { it.native?.pluginId == "mobile-use" }
            val session = UUID.randomUUID().toString()
            val other = UUID.randomUUID().toString()
            container.storage.sessions.create(session, "Native repair fixture", null, null, 0)
            container.storage.sessions.create(other, "Unselected repair fixture", null, null, 0)
            val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
            val original = lines.lines(MobileUseGrantStore.CONFIG_KEY)
            val backend =
                com.helix.app.eval
                    .OwnedAccessibilityBackend(app)
            try {
                backend.connect()
                MobileUseGrantStore(lines::lines, lines::setLines, { null })
                    .configureGlobal(setOf(app.packageName), false)
                val configuration = lines.lines(MobileUseGrantStore.CONFIG_KEY)
                service.catalog.select(session, record.id, true)
                val selected = service.catalog.selected(session)
                val unselected = service.catalog.selected(other)
                val permissions = container.storage.sessionPermissionConfigs.forSession(session)
                val otherPermissions = container.storage.sessionPermissionConfigs.forSession(other)
                service.repairNative(record.id)
                assertEquals(selected, service.catalog.selected(session))
                assertEquals(unselected, service.catalog.selected(other))
                assertEquals(permissions, container.storage.sessionPermissionConfigs.forSession(session))
                assertEquals(otherPermissions, container.storage.sessionPermissionConfigs.forSession(other))
                assertEquals(configuration, lines.lines(MobileUseGrantStore.CONFIG_KEY))
            } finally {
                try {
                    lines.setLines(MobileUseGrantStore.CONFIG_KEY, original)
                    container.storage.deleteSessionPermanently(session)
                    container.storage.deleteSessionPermanently(other)
                } finally {
                    backend.close()
                }
            }
        }
}
