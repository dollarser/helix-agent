package com.helix.runtime.proot.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.DetachedJobProtocol
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobSpec
import com.helix.runtime.proot.ipc.ProotJobWire
import com.helix.runtime.proot.ipc.ProotRuntimeProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Authorized host calls only. Does not grant permission, enumerate jobs, or retry a failed submission. */
class DetachedJobClient(
    context: Context,
) {
    private val context = context.applicationContext

    data class Reply(
        val status: Byte,
        val record: ProotJobRecord?,
        val refusal: String?,
    ) {
        val accepted: Boolean get() = status == ProotRuntimeProtocol.REPLY_JOB_ACCEPTED
    }

    fun submit(
        binding: DetachedJobBinding,
        spec: ProotJobSpec,
        remainingBudgetMs: Long,
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
    ): Reply =
        input.use {
            output.use {
                require(binding.matches(spec))
                request(binding, DetachedJobProtocol.SUBMIT) { data ->
                    data.writeLong(remainingBudgetMs)
                    ProotJobWire.writeSpec(data, spec, input, output)
                }
            }
        }

    fun query(binding: DetachedJobBinding): Reply = request(binding, DetachedJobProtocol.QUERY)

    fun cancel(binding: DetachedJobBinding): Reply = request(binding, DetachedJobProtocol.CANCEL)

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    private fun request(
        binding: DetachedJobBinding,
        code: Int,
        write: (Parcel) -> Unit = {},
    ): Reply {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Detached IPC must run off the UI thread" }
        val ready = CountDownLatch(1)
        var endpoint: IBinder? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    endpoint = service
                    ready.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    endpoint = null
                    ready.countDown()
                }

                override fun onNullBinding(name: ComponentName) {
                    ready.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    endpoint = null
                    ready.countDown()
                }
            }
        var bound = false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            bound =
                context.bindService(
                    Intent().setComponent(ComponentName(context.packageName, DetachedJobProtocol.SERVICE)),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            if (!bound || !ready.await(20, TimeUnit.SECONDS)) return unavailable()
            val binder = endpoint ?: return unavailable()
            data.writeInterfaceToken(DetachedJobProtocol.DESCRIPTOR)
            DetachedJobProtocol.writeBinding(data, binding)
            write(data)
            if (!binder.transact(code, data, reply, 0)) return unavailable()
            val (status, payload) = ProotJobWire.readJobReply(reply)
            val hasRecord =
                status in
                    setOf(
                        ProotRuntimeProtocol.REPLY_JOB_ACCEPTED,
                        ProotRuntimeProtocol.REPLY_JOB_DUPLICATE,
                        ProotRuntimeProtocol.REPLY_JOB_STATE,
                    )
            val record = if (hasRecord) ProotJobRecordCodec.parse(requireNotNull(payload)) else null
            require(
                record == null ||
                    (
                        record.jobId == binding.jobId && record.executionId == binding.executionId &&
                            record.inputManifestSha256 == binding.inputManifestSha256
                    ),
            )
            return Reply(status, record, if (status == ProotRuntimeProtocol.REPLY_JOB_REJECTED) payload else null)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return unavailable()
        } catch (_: Exception) {
            return unavailable()
        } finally {
            data.recycle()
            reply.recycle()
            if (bound) context.unbindService(connection)
        }
    }

    private fun unavailable() =
        Reply(ProotRuntimeProtocol.REPLY_JOB_UNAVAILABLE, null, "UNAVAILABLE_QUERY_ORIGINAL_JOB")
}
