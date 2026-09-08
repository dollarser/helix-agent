package com.helix.app.eval

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.proot.ProotResultRecovery
import com.helix.app.proot.ProotResultStore
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotResultClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import org.junit.Assert.assertNotNull
import java.io.File
import java.util.Properties

/** Uses real persistence and Binder ACK; only the pause at the selected boundary is injected. */
internal fun pauseProotResultRecovery(
    context: Context,
    storage: HelixStorage,
    facts: Properties,
) {
    val supervisor = ProotRuntimeSupervisor(context)
    val jobs = ProotJobClient(supervisor)
    val results = ProotResultClient(supervisor)
    val store =
        ProotResultStore(
            storage,
            File(context.filesDir, "workspaces/app"),
            File(context.cacheDir, "proot-results"),
        )
    val turn = facts.getProperty("turn")
    val call = facts.getProperty("call")
    val boundary = InstrumentationRegistry.getArguments().getString("proot.goal.result.boundary")
    check(boundary in setOf("persisted", "acknowledged"))
    val recovery =
        ProotResultRecovery(
            storage,
            store,
            { (jobs.query(it) as? ProotJobClient.JobStateOutcome.Ok)?.record },
            results::fetch,
            { record ->
                assertNotNull(store.readLocal(turn, call))
                if (boundary == "acknowledged") assertNotNull(results.acknowledge(record))
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    2,
                    Bundle().apply {
                        putString("stream", "PROOT_RESULT_BOUNDARY_READY pid=${android.os.Process.myPid()}\n")
                    },
                )
                Thread.sleep(30000)
                error("Host did not kill the selected result boundary")
            },
        )
    recovery.recover(turn, call, localOnly = false)
}
