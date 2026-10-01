package com.helix.runtime.cli.client

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CliReplayMaintenanceWireDeviceTest {
    @Test fun completeInventoryAndExactReceiptAreAccepted() {
        val entry = CliReplayEntry("a".repeat(64), "b".repeat(64), null, 100)
        val page = CliReplayPage(listOf(entry), null)
        val inventory = reply { it.writeString(CliReplayMaintenance.encode(page)) }
        assertEquals(page, CliReplayMaintenanceWire.page(inventory, null))
        val removal =
            reply {
                it.writeInt(1)
                it.writeInt(0)
                it.writeInt(0)
                it.writeLong(100)
                it.writeInt(0)
            }
        assertEquals(CliReplayPruneResult(1, 0, 0, 100), CliReplayMaintenanceWire.prune(removal, listOf(entry)))
    }

    @Test fun malformedAndTruncatedRepliesCannotConfirmDeletion() {
        val entry = CliReplayEntry("a".repeat(64), "b".repeat(64), null, 100)
        assertThrows(IllegalArgumentException::class.java) {
            CliReplayMaintenanceWire.prune(reply { it.writeInt(1) }, listOf(entry))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliReplayMaintenanceWire.prune(
                reply {
                    it.writeInt(1)
                    it.writeInt(1)
                    it.writeInt(0)
                    it.writeLong(100)
                    it.writeInt(0)
                },
                listOf(entry),
            )
        }
    }

    @Test fun cursorMustAdvanceAndReplyMustBeBounded() {
        val entry = CliReplayEntry("a".repeat(64), "b".repeat(64), null, 100)
        assertThrows(IllegalArgumentException::class.java) {
            CliReplayMaintenanceWire.page(
                reply {
                    it.writeString(CliReplayMaintenance.encode(CliReplayPage(listOf(entry), null)))
                },
                entry.key,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliReplayMaintenanceWire.page(
                reply { it.writeString("x".repeat(CliReplayMaintenance.MAX_WIRE_CHARS + 32)) },
                null,
            )
        }
    }

    @Test fun binderDeathDoesNotReplayTheOperation() {
        var calls = 0
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    calls++
                    throw DeadObjectException()
                }
            }
        assertThrows(DeadObjectException::class.java) { CliReplayMaintenanceWire.prune(binder, emptyList()) }
        assertEquals(1, calls)
    }

    private fun reply(write: (Parcel) -> Unit): Binder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                data.enforceInterface(CliRuntimeProtocol.DESCRIPTOR)
                requireNotNull(reply).writeInt(CliRuntimeProtocol.REPLY_OK)
                write(reply)
                return true
            }
        }
}
