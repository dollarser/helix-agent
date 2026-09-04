package com.helix.app.foreground

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device acceptance for the HXA-066 `dataSync` foreground service. It starts as a real
 * `dataSync`-typed foreground service — a `startForegroundService` with that type throws if the
 * type is not declared in the manifest or its `FOREGROUND_SERVICE_DATA_SYNC` grant is missing, so
 * a clean start plus a posted notification is the green light for the type — posts a stoppable
 * notification, and stops when the stop action is used or when the controlling turn waits for the
 * user. On API 35+ the Android 15 dataSync-limit callback ([android.app.Service.onTimeout]) stops
 * it. The 6 h / 24 h bound itself is a pure predicate pinned in [DataSyncForegroundControllerTest].
 */
@RunWith(AndroidJUnit4::class)
class DataSyncForegroundServiceDeviceTest {
    @Test
    fun dataSyncForegroundStartsPostsAStoppableNotificationAndStops() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        context.startForegroundService(DataSyncForegroundService.intent(context))
        val manager = notifications(context)
        waitFor("the foreground notification to be posted") {
            manager.activeNotifications.any { it.id == DataSyncForegroundService.NOTIFICATION_ID }
        }
        val posted =
            manager.activeNotifications.first { it.id == DataSyncForegroundService.NOTIFICATION_ID }
        assertEquals(DataSyncForegroundService.CHANNEL_ID, posted.notification.channelId)
        assertTrue(
            "the notification must expose a stop action",
            (posted.notification.actions?.size ?: 0) >= 1,
        )
        // Fire the stop action through the service (the PendingIntent targets ACTION_STOP).
        context.startService(
            Intent(context, DataSyncForegroundService::class.java)
                .setAction(DataSyncForegroundService.ACTION_STOP),
        )
        waitFor("the stop action to tear the service down") {
            manager.activeNotifications.none { it.id == DataSyncForegroundService.NOTIFICATION_ID }
        }
    }

    @Test
    fun controllerStopsTheForegroundServiceWhenTheTurnWaitsForApproval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        val manager = notifications(context)
        val controller = DataSyncForegroundController(AndroidForegroundServiceLauncher(context))
        controller.onTurnState(TurnState.WAITING_MODEL)
        waitFor("the turn transport to bring the foreground service up") {
            manager.activeNotifications.any { it.id == DataSyncForegroundService.NOTIFICATION_ID }
        }
        controller.onTurnState(TurnState.WAITING_APPROVAL)
        waitFor("waiting for approval to stop the foreground service") {
            manager.activeNotifications.none { it.id == DataSyncForegroundService.NOTIFICATION_ID }
        }
    }

    @Test
    fun dataSyncForegroundStopsOnTheApi35TimeoutCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // onTimeout is an API 35 callback; on the API 29 device that path does not exist
            // (start / stop / the stop action are covered by the other two tests).
            return
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        val manager = notifications(context)
        context.startForegroundService(DataSyncForegroundService.intent(context))
        waitFor("the service instance to be bound") {
            DataSyncForegroundService.runningInstance.get() != null
        }
        val running = DataSyncForegroundService.runningInstance.get() ?: return
        DataSyncForegroundService::class.java
            .getMethod("onTimeout", java.lang.Integer.TYPE, java.lang.Integer.TYPE)
            .invoke(running, 0, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        waitFor("onTimeout to tear the service down") {
            manager.activeNotifications.none { it.id == DataSyncForegroundService.NOTIFICATION_ID } &&
                DataSyncForegroundService.runningInstance.get() == null
        }
    }

    private fun notifications(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("timed out waiting for: $what", condition())
    }

    private fun grantNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
}
