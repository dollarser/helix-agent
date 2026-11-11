package com.helix.extensions.mobileuse.automation

import android.os.Parcel
import com.helix.extensions.mobileuse.automation.backend.PrivilegedSemanticParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SnapshotTruncationParcelDeviceTest {
    @Test fun fullCollectionBudgetAndVisibilitySurviveParcelWhileExcessCountIsRejected() {
        val base =
            AutomationSnapshotNode(
                "token",
                null,
                0,
                "View",
                "row",
                null,
                "fixture:id/row",
                AutomationNodeBounds(0, 0, 100, 100),
                true,
                false,
                false,
                false,
                true,
            )
        val nodes =
            (1..NodeTokenRegistry.MAX_TOKENS).map {
                base.copy(
                    token = "token$it",
                    visibleToUser = it % 2 == 0,
                    hintText = ".ext",
                    isShowingHintText =
                        when (it % 3) {
                            0 -> null
                            1 -> true
                            else -> false
                        },
                )
            }
        val snapshot = AutomationSnapshot("synthetic.fixture", 7, 1, Instant.EPOCH, nodes, false)
        val parcel = Parcel.obtain()
        try {
            PrivilegedSemanticParcel.write(parcel, AutomationSnapshotResult(AutomationSnapshotStatus.SUCCESS, snapshot))
            assertTrue(parcel.dataSize() < 512 * 1024)
            parcel.setDataPosition(0)
            assertEquals(nodes, requireNotNull(PrivilegedSemanticParcel.read(parcel)?.snapshot).nodes)
            parcel.setDataPosition(0)
            PrivilegedSemanticParcel.write(
                parcel,
                AutomationSnapshotResult(AutomationSnapshotStatus.SUCCESS, snapshot.copy(nodes = nodes + base)),
            )
            parcel.setDataPosition(0)
            assertThrows(IllegalArgumentException::class.java) { PrivilegedSemanticParcel.read(parcel) }
        } finally {
            parcel.recycle()
        }
    }

    @Test fun malformedFieldAndAggregateTextBudgetsAreRejectedAtTheParcelBoundary() {
        val base =
            AutomationSnapshotNode(
                "token",
                null,
                0,
                "View",
                "x".repeat(256),
                null,
                null,
                AutomationNodeBounds(0, 0, 100, 100),
                true,
                false,
                false,
                false,
                true,
            )
        val cases =
            listOf(
                listOf(base.copy(text = "x".repeat(257))),
                listOf(base.copy(hintText = "h".repeat(257))),
                List(260) {
                    base.copy(token = "t$it")
                },
            )
        for (nodes in cases) {
            val parcel = Parcel.obtain()
            try {
                val snapshot = AutomationSnapshot("synthetic.fixture", 7, 1, Instant.EPOCH, nodes, false)
                PrivilegedSemanticParcel.write(
                    parcel,
                    AutomationSnapshotResult(AutomationSnapshotStatus.SUCCESS, snapshot),
                )
                parcel.setDataPosition(0)
                assertThrows(IllegalArgumentException::class.java) { PrivilegedSemanticParcel.read(parcel) }
            } finally {
                parcel.recycle()
            }
        }
    }

    @Test fun missingChildrenAndAllReasonsSurviveTheActualAndroidParcel() {
        assertEquals(SnapshotTruncationReasons.all, roundTrip(SnapshotTruncationReasons.all))
        assertEquals(setOf("MISSING_CHILD"), roundTrip(setOf("MISSING_CHILD")))
        assertThrows(IllegalArgumentException::class.java) { roundTrip(setOf("INVENTED_REASON")) }
    }

    private fun roundTrip(reasons: Set<String>): Set<String> {
        val parcel = Parcel.obtain()
        return try {
            val snapshot = AutomationSnapshot("synthetic.fixture", 7, 1, Instant.EPOCH, emptyList(), true, reasons)
            PrivilegedSemanticParcel.write(parcel, AutomationSnapshotResult(AutomationSnapshotStatus.SUCCESS, snapshot))
            parcel.setDataPosition(0)
            requireNotNull(PrivilegedSemanticParcel.read(parcel)?.snapshot).truncationReasons
        } finally {
            parcel.recycle()
        }
    }
}
