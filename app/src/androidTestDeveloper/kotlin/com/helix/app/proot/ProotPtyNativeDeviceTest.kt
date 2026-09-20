package com.helix.app.proot

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native production I/O in :proot. This is not the unimplemented product terminal session journey. */
@RunWith(AndroidJUnit4::class)
class ProotPtyNativeDeviceTest {
    @Test fun prootInteractiveTtyUnicodeResizeCtrlCAndPythonRepl() = probe(1)

    @Test fun execFailureReportsExit127() = probe(2)

    @Test fun twentyClosedProcessesDoNotLeakDescriptorsOrSignalReapedPids() = probe(3)

    @Test fun nativeBoundsRejectWithoutCorruptingIoAndSignalExitIsReported() = probe(4)

    @Test fun shellExitCleansBackgroundAndDetachedJobsWithoutKillingAnotherOwner() = probe(5)

    @Test fun explicitProotQuitCleansForegroundBackgroundAndDetachedJobs() = probe(6)

    @Test fun sessionWorkerDrainsReconnectsResizesAndPersistsUserStop() = probe(7)

    private fun probe(code: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var endpoint: IBinder? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    endpoint = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    endpoint = null
                }
            }
        val intent =
            Intent().setComponent(
                ComponentName(context.packageName, "com.helix.runtime.proot.app.PtyNativeProbeService"),
            )
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            assertTrue(connected.await(20, TimeUnit.SECONDS))
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.helix.runtime.proot.PtyNativeProbe")
                assertTrue(checkNotNull(endpoint).transact(code, data, reply, 0))
                reply.readException()
                assertNotEquals(Process.myPid(), reply.readInt())
                assertEquals("OK", reply.readString())
            } finally {
                data.recycle()
                reply.recycle()
            }
        } finally {
            if (bound) context.unbindService(connection)
        }
    }
}
