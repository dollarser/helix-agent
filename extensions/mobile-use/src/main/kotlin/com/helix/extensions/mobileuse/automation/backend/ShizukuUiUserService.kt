package com.helix.extensions.mobileuse.automation.backend

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep

internal object ShizukuUiProtocol {
    const val DESCRIPTOR = "com.helix.app.automation.ShizukuUi"
    const val GET_UID = IBinder.FIRST_CALL_TRANSACTION + 100
    const val CLICK_MATCH = IBinder.FIRST_CALL_TRANSACTION + 101
    const val DEVICE = IBinder.FIRST_CALL_TRANSACTION + 102
    const val DESTROY_AIDL = 16_777_114
    const val DESTROY_REMOTE = 16_777_115
}

@Keep
class ShizukuUiUserService
    @Keep
    constructor(
        context: Context,
    ) : Binder() {
        private val ownerUid = context.applicationInfo.uid.also { require(it >= 10_000) }
        private var hierarchy: ShizukuHierarchy? = null

        // Release the partially connected platform object, then rethrow unchanged.
        @Suppress("TooGenericExceptionCaught")
        private fun screen(guard: IBinder): ShizukuHierarchy {
            check(ShizukuExecutionGuard.check(guard)) { "GRANT_LOST" }
            return hierarchy ?: ShizukuHierarchy().also {
                try {
                    it.connect()
                } catch (failure: Exception) {
                    it.close()
                    throw failure
                }
                hierarchy = it
            }
        }

        @Synchronized
        fun close() {
            hierarchy?.close()
            hierarchy = null
        }

        init {
            attachInterface(null, ShizukuUiProtocol.DESCRIPTOR)
        }

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean =
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(ShizukuUiProtocol.DESCRIPTOR)
                    true
                }

                ShizukuUiProtocol.GET_UID -> {
                    require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
                    data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeInt(Process.myUid())
                    true
                }

                ShizukuUiProtocol.CLICK_MATCH -> {
                    require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
                    data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
                    val selector =
                        ShizukuUiSelector(
                            requireNotNull(data.readString()),
                            requireNotNull(data.readString()),
                            requireNotNull(data.readString()),
                        )
                    val guard = requireNotNull(data.readStrongBinder())
                    require(data.dataAvail() == 0) { "UNEXPECTED_ARGUMENTS" }
                    val result = synchronized(this) { ShizukuUiCommands(guard, screen(guard)).clickMatch(selector) }
                    reply?.writeNoException()
                    reply?.writeString(result)
                    true
                }

                ShizukuUiProtocol.DEVICE -> {
                    require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
                    data.enforceInterface(ShizukuUiProtocol.DESCRIPTOR)
                    val request = PrivilegedDeviceParcel.readRequest(data)
                    val guard = requireNotNull(data.readStrongBinder())
                    require(data.dataAvail() == 0) { "UNEXPECTED_ARGUMENTS" }
                    synchronized(
                        this,
                    ) { PrivilegedDeviceService(guard, screen(guard)).execute(request, requireNotNull(reply)) }
                    true
                }

                ShizukuUiProtocol.DESTROY_AIDL,
                ShizukuUiProtocol.DESTROY_REMOTE,
                -> {
                    require(getCallingUid() in setOf(ownerUid, 0, 2000)) { "UNTRUSTED_CALLER" }
                    reply?.writeNoException()
                    Thread {
                        try {
                            close()
                        } finally {
                            System.exit(0)
                        }
                    }.start()
                    true
                }

                else -> {
                    super.onTransact(code, data, reply, flags)
                }
            }
    }
