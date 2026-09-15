package com.helix.core.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-200 Gap 2: the pure "is this tool new in the current build?" decision behind the NEW_DEFAULT
 * default (ADR-0052 point 1; 2026-09-15 baseline mechanism). The decision is driven ONLY by three
 * trusted versionCode facts (current / founding / first-seen) persisted by the app's trusted
 * registration path — never by an empty preference record or a model claim. The cases below pin:
 * a fresh install (founding == current) is never "new"; a tool that first appears in a post-founding
 * build is "new" for that build and stays so across restarts of the same build; it ages to "old"
 * once a later build becomes current; and a missing marker or founding baseline yields "old".
 */
class ToolBaselineTest {
    @Test
    fun aFreshInstallIsNeverNew() {
        // founding == current == firstSeen: a bundled tool registered on a fresh install is part of
        // the founding baseline, so it is OLD — a fresh install never forces ASK on its own tools
        // (point 1 "既有工具 UNSET").
        assertFalse(
            ToolBaseline.isNewDefault(currentVersionCode = 1, foundingVersionCode = 1, firstSeenVersionCode = 1),
        )
    }

    @Test
    fun aToolFirstSeenInAnUpgradeBuildIsNew() {
        // The upgrade to current=2 introduced the tool (firstSeen=2) after the founding=1 build:
        // it is NEW in this build.
        assertTrue(
            ToolBaseline.isNewDefault(currentVersionCode = 2, foundingVersionCode = 1, firstSeenVersionCode = 2),
        )
    }

    @Test
    fun aToolFirstSeenInAFoundingBuildStaysOldAfterAnUpgrade() {
        // A tool that was in the founding build (firstSeen=1) is OLD once an upgrade to current=2 is
        // running: the upgrade did not introduce it.
        assertFalse(
            ToolBaseline.isNewDefault(currentVersionCode = 2, foundingVersionCode = 1, firstSeenVersionCode = 1),
        )
    }

    @Test
    fun aNewToolAgesOutOfNewnessOnTheNextUpgrade() {
        // firstSeen=2 (introduced at build 2) but current=3: it is no longer "new in the current
        // build" — the NEW window is one build. It returns to normal unconfigured (UNSET) handling.
        assertFalse(
            ToolBaseline.isNewDefault(currentVersionCode = 3, foundingVersionCode = 1, firstSeenVersionCode = 2),
        )
    }

    @Test
    fun restartInTheSameBuildIsStable() {
        // The decision is a pure function of the persisted facts, so re-evaluating it after a
        // restart of the SAME build (current=2, founding=1, firstSeen=2) gives the same NEW answer —
        // it does not depend on in-memory state or process uptime.
        assertTrue(
            ToolBaseline.isNewDefault(currentVersionCode = 2, foundingVersionCode = 1, firstSeenVersionCode = 2),
        )
    }

    @Test
    fun aMissingFoundingBaselineMeansNeverNew() {
        // The trusted path has never run (fresh database, founding null): there is no baseline, so
        // nothing is new even if a per-tool marker somehow exists.
        assertFalse(
            ToolBaseline.isNewDefault(currentVersionCode = 1, foundingVersionCode = null, firstSeenVersionCode = 1),
        )
    }

    @Test
    fun aToolWithNoMarkerIsNeverNew() {
        // A tool never trustedly registered (firstSeen null) has no basis to be called new — it
        // stays UNSET; it is NOT flagged new from the mere absence of a preference record.
        assertFalse(
            ToolBaseline.isNewDefault(currentVersionCode = 2, foundingVersionCode = 1, firstSeenVersionCode = null),
        )
    }

    @Test
    fun bothFactsMissingAreNeverNew() {
        assertFalse(
            ToolBaseline.isNewDefault(currentVersionCode = 3, foundingVersionCode = null, firstSeenVersionCode = null),
        )
    }
}
