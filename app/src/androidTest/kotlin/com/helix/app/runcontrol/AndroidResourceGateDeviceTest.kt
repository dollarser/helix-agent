package com.helix.app.runcontrol

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** HXA-099 platform fixture: real lifecycle plus real MemoryInfo/thermal sampling. */
@RunWith(AndroidJUnit4::class)
class AndroidResourceGateDeviceTest {
    @Test
    fun backgroundAlwaysLowersAndForegroundReSamplesLivePlatformState() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val probe = PlatformDeviceResourceProbe(application)
        val gate = AndroidResourceGate(probe)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            val foreground = gate.allowance()
            assertTrue(foreground == 1 || foreground == 2)

            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(1, gate.allowance())

            scenario.moveToState(Lifecycle.State.RESUMED)
            assertEquals(
                if (probe.isLowMemory() || probe.isThermallyConstrained()) 1 else 2,
                gate.allowance(),
            )
        }
    }
}
