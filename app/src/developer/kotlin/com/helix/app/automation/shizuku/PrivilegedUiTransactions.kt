package com.helix.app.automation.shizuku

import android.os.IBinder
import android.os.Parcel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicBoolean

/** Shared typed protocol for an already authorized RootService or Shizuku UserService. */
internal object PrivilegedUiTransactions {
    fun uid(binder: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuUiProtocol.DESCRIPTOR)
            check(binder.transact(ShizukuUiProtocol.GET_UID, data, reply, 0)) { "UI_UID_TRANSACT_FAILED" }
            reply.readException()
            reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun click(
        binder: IBinder,
        selector: ShizukuUiSelector,
        allowed: (Int, Int, Int) -> Boolean,
        mayFinish: () -> Boolean,
    ): JsonObject {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val active = AtomicBoolean(true)
        return try {
            data.writeInterfaceToken(ShizukuUiProtocol.DESCRIPTOR)
            data.writeString(selector.packageName)
            data.writeString(selector.resourceId)
            data.writeString(selector.text)
            data.writeStrongBinder(
                ShizukuExecutionGuard(
                    allowed = { x, y, rotation -> active.get() && allowed(x, y, rotation) },
                    mayFinish = { active.get() && mayFinish() },
                ),
            )
            check(binder.transact(ShizukuUiProtocol.CLICK_MATCH, data, reply, 0)) { "UI_CLICK_TRANSACT_FAILED" }
            reply.readException()
            Json.parseToJsonElement(requireNotNull(reply.readString())).jsonObject
        } finally {
            active.set(false)
            data.recycle()
            reply.recycle()
        }
    }
}
