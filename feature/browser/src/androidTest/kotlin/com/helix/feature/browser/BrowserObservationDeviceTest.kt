package com.helix.feature.browser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.policy.BrowserTabScope
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.tools.browser.BrowserObservationContext
import com.helix.tools.browser.BrowserWaitCondition
import com.helix.tools.browser.BrowserWaitRequest
import com.helix.tools.browser.BrowserWaitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class BrowserObservationDeviceTest {
    @Test fun scopedExistingTabsDoNotExposeOtherSessionsOrManualPages() =
        withBridge { c, b ->
            val ours = b.open("data:text/html,<h1>Owned</h1>", "s").tabId
            val other = b.open("data:text/html,<h1>Private</h1>", "other").tabId
            val manual = b.open("data:text/html,<h1>Manual</h1>").tabId
            settle(c, manual)
            val discovered = b.tabs(BrowserObservationContext("s", null))
            assertEquals(listOf(ours), discovered.tabs.map { it.tabId })
            assertFalse(discovered.tabs.any { it.tabId == other || it.tabId == manual })
            assertTrue(b.tabs(BrowserObservationContext(null, null)).tabs.isEmpty())
            assertEquals(
                listOf(manual),
                b
                    .tabs(
                        BrowserObservationContext(
                            null,
                            BrowserTabScope(manual, c.tab(manual)!!.navigationGeneration.toInt()).toScopeRef(),
                        ),
                    ).tabs
                    .map { it.tabId },
            )
        }

    @Test fun delayedTargetAndAbsentConditionsReturnRealObservations() =
        withBridge { c, b ->
            val id = b.open("data:text/html,<button>Loading</button>", "s").tabId
            settle(c, id)
            onMain {
                c
                    .hostView(
                        id,
                    )!!
                    .evaluateJavascript(
                        "setTimeout(()=>document.querySelector('button').textContent='Ready',350);" + "'scheduled'",
                        null,
                    )
            }
            val found = b.waitFor(request(id, "Ready"))
            assertEquals(BrowserWaitStatus.SATISFIED, found.status)
            assertEquals(1, found.observation!!.matchCount)
            assertFalse(found.observation!!.observationExpired)
            assertEquals(
                BrowserWaitStatus.SATISFIED,
                b.waitFor(request(id, "Loading", BrowserWaitCondition.ABSENT)).status,
            )
            assertEquals(BrowserWaitStatus.TIMED_OUT, b.waitFor(request(id, "Missing", timeout = 200)).status)
        }

    @Test fun deadlineCancellationAndWrongScopeDoNotExecuteActions() =
        withBridge { c, b ->
            val id = b.open("data:text/html,<button>Still</button>", "s").tabId
            settle(c, id)
            assertEquals(
                BrowserWaitStatus.OUT_OF_SCOPE,
                b.waitFor(request(id, "Still").copy(context = BrowserObservationContext("other", null))).status,
            )
            val started = SystemClock.elapsedRealtime()
            assertEquals(
                BrowserWaitStatus.TIMED_OUT,
                b.waitFor(request(id, "Missing").copy(deadline = Instant.now().plusMillis(120))).status,
            )
            assertTrue(SystemClock.elapsedRealtime() - started < 1000)
            val cancelled = AtomicBoolean(false)
            Handler(Looper.getMainLooper()).postDelayed({ cancelled.set(true) }, 150)
            assertEquals(
                BrowserWaitStatus.CANCELLED,
                b.waitFor(request(id, "Missing").copy(isCancelled = cancelled::get)).status,
            )
            assertEquals(
                "Still",
                b
                    .snapshot(id)
                    .nodes
                    .single()
                    .text,
            )
        }

    @Test fun navigationInvalidatesWaitAndOldToken() =
        withBridge { c, b ->
            val id = b.open("data:text/html,<button>Old</button>", "s").tabId
            settle(c, id)
            val token =
                b
                    .snapshot(id)
                    .nodes
                    .single()
                    .token
            Handler(Looper.getMainLooper()).postDelayed({ c.navigate(id, "data:text/html,<button>New</button>") }, 180)
            assertEquals(BrowserWaitStatus.PAGE_CHANGED, b.waitFor(request(id, "Missing")).status)
            settle(c, id)
            assertEquals(com.helix.tools.browser.ActionStatus.STALE_TOKEN, b.click(id, token).status)
            assertEquals(
                "New",
                b
                    .snapshot(id)
                    .nodes
                    .single()
                    .text,
            )
        }

    @Test fun hostRetirementEndsOriginalWaitWithoutPretendingPageAvailable() =
        withBridge { c, b ->
            val id = b.open("data:text/html,<button>Old owner</button>", "s").tabId
            settle(c, id)
            Handler(Looper.getMainLooper()).postDelayed({ c.destroy() }, 180)
            assertEquals(BrowserWaitStatus.HOST_UNAVAILABLE, b.waitFor(request(id, "Missing")).status)
            assertTrue(b.tabs(BrowserObservationContext("s", null)).tabs.none { it.hostAvailable })
        }

    @Test fun duplicateLabelsAreAmbiguousAndReturnedHitsAreBounded() =
        withBridge { c, b ->
            val id = b.open("data:text/html," + "<button>Same</button>".repeat(80), "s").tabId
            settle(c, id)
            assertTrue(b.snapshot(id).ok)
            val found = b.find(id, "Same")
            assertEquals(80, found.matchCount)
            assertEquals(50, found.matches.size)
            assertTrue(found.truncated)
            val waited = b.waitFor(request(id, "Same"))
            assertEquals(BrowserWaitStatus.SATISFIED, waited.status)
            assertEquals(80, waited.observation!!.matchCount)
        }

    @Test fun ownerReplacementCannotRebindTheWaitingCallToItsNewPage() {
        BrowserActivityFixture().use { fixture ->
            val c = fixture.controller
            val directory =
                java.io
                    .File(
                        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                        "owner-wait-test",
                    ).apply { mkdirs() }
            val b =
                BrowserToolBridgeImpl(c, WorkspaceArtifactStore(ScopeRootResolver { directory.toPath() }), "fixture")
            val id = b.open("data:text/html,<button>Original</button>", "s").tabId
            settle(c, id)
            fixture.scenario.onActivity { activity ->
                val old = activity.owner
                Handler(Looper.getMainLooper()).postDelayed({
                    val replacement = BrowserViewOwner(activity)
                    c.attach(replacement)
                    activity.owner = replacement
                    c.resume(replacement)
                    c.detach(old)
                    c.navigate(id, "data:text/html,<button>Replacement</button>")
                }, 180)
            }
            assertEquals(BrowserWaitStatus.HOST_UNAVAILABLE, b.waitFor(request(id, "Replacement")).status)
            settle(c, id)
            assertEquals(BrowserWaitStatus.SATISFIED, b.waitFor(request(id, "Replacement")).status)
        }
    }

    private fun request(
        id: String,
        query: String,
        condition: BrowserWaitCondition = BrowserWaitCondition.PRESENT,
        timeout: Int = 3000,
    ) = BrowserWaitRequest(
        id,
        query,
        condition,
        timeout,
        BrowserObservationContext(
            "s",
            null,
        ),
        Instant.now().plusSeconds(5),
        {
            false
        },
    )

    private fun withBridge(block: (BrowserController, BrowserToolBridgeImpl) -> Unit) {
        BrowserActivityFixture().use { fixture ->
            val directory =
                java.io
                    .File(
                        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                        "observation-test",
                    ).apply {
                        mkdirs()
                    }
            val bridge =
                BrowserToolBridgeImpl(
                    fixture.controller,
                    WorkspaceArtifactStore(ScopeRootResolver { directory.toPath() }),
                    "fixture",
                )
            try {
                block(fixture.controller, bridge)
            } finally {
                onMain { fixture.controller.destroy() }
            }
        }
    }

    private fun settle(
        c: BrowserController,
        id: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < deadline) {
            val tab = c.tab(id)!!
            if (!tab.isLoading && tab.navigationGeneration > 0) return
            SystemClock.sleep(30)
        }
        error("fixture page did not settle")
    }

    @Test fun dispatchedMutationLosingItsHostIsUnknownRatherThanSafeToReplay() {
        BrowserActivityFixture().use { fixture ->
            val c = fixture.controller
            val tab =
                java.util.concurrent.atomic
                    .AtomicReference<String>()
            onMain { tab.set(c.openTab("data:text/html,<button>Effect</button>").tabId) }
            val id = tab.get()
            settle(c, id)
            val result = java.util.concurrent.CompletableFuture<EvalFixedOutcome>()
            val callbacks =
                java.util.concurrent.atomic
                    .AtomicInteger()
            onMain {
                c.evaluateFixed(
                    id,
                    "(()=>{window.effectCount=(window.effectCount||0)+1;" +
                        "const end=Date.now()+800;while(Date.now()<end){};" +
                        "return JSON.stringify({status:'performed',tag:'button',role:'button'});})()",
                ) {
                    callbacks.incrementAndGet()
                    result.complete(it)
                }
                Handler(Looper.getMainLooper()).postDelayed({ c.destroy() }, 50)
            }
            val outcome = result.get(5, java.util.concurrent.TimeUnit.SECONDS) as EvalFixedOutcome.Result
            assertEquals(null, outcome.raw)
            assertEquals(
                com.helix.tools.browser.ActionStatus.TIMED_OUT,
                BrowserToolResultMapper.mapAction(outcome.raw, 0).status,
            )
            SystemClock.sleep(1000)
            assertEquals(1, callbacks.get())
            // clear() releases pages but deliberately retains the live Activity binding.
            assertTrue(c.hostAvailable())
            assertEquals(null, c.hostView(id))
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
