package com.helix.app.foreground

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.model.TurnState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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
    @Before
    fun stopAnyPreviousFixtureService() {
        stopFixtureService()
    }

    @After
    fun stopFixtureServiceAfterTest() {
        stopFixtureService()
    }

    @Test
    fun dataSyncForegroundStartsPostsAStoppableNotificationAndStops() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        AndroidForegroundServiceLauncher(context).start()
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
        // Exercise the actual action attached to the posted notification.
        posted.notification.actions
            .first()
            .actionIntent
            .send()
        waitFor("the stop action to tear the service down") {
            manager.activeNotifications.none { it.id == DataSyncForegroundService.NOTIFICATION_ID }
        }
    }

    @Test
    fun rapidStartAndStopDoesNotLeaveAPendingForegroundPromotionOrNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        val launcher = AndroidForegroundServiceLauncher(context)
        repeat(20) {
            launcher.start()
            Thread.sleep(10)
            launcher.stop()
        }
        // Android reports a missed promotion asynchronously after the short turn has ended.
        Thread.sleep(12_000)
        waitFor("rapid transports to leave no running service or notification") {
            DataSyncForegroundService.runningInstance.get() == null &&
                notifications(context).activeNotifications.none { it.id == DataSyncForegroundService.NOTIFICATION_ID }
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
    @SdkSuppress(minSdkVersion = 35)
    fun dataSyncForegroundStopsOnTheApi35TimeoutCallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        val manager = notifications(context)
        AndroidForegroundServiceLauncher(context).start()
        waitFor("the new service instance to enter the foreground") {
            DataSyncForegroundService.runningInstance.get() != null &&
                manager.activeNotifications.any { it.id == DataSyncForegroundService.NOTIFICATION_ID }
        }
        val running = requireNotNull(DataSyncForegroundService.runningInstance.get())
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

    private fun stopFixtureService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(DataSyncForegroundService.intent(context))
        val deadline = System.currentTimeMillis() + 5_000
        while (DataSyncForegroundService.runningInstance.get() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(
            "a previous dataSync fixture service did not stop",
            DataSyncForegroundService.runningInstance.get() == null,
        )
    }

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
