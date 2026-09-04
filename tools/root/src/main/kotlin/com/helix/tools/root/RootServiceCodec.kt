package com.helix.tools.root

import android.os.IBinder
import android.os.Parcel

internal object RootServiceCodec {
    fun transact(
        binder: IBinder,
        request: RootOperationRequest,
    ): RootOperationResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RootServiceProtocol.DESCRIPTOR)
            writeRequest(data, request)
            check(binder.transact(RootServiceProtocol.EXECUTE_HIGH_LEVEL, data, reply, 0))
            reply.readException()
            readResult(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun readRequest(parcel: Parcel): RootOperationRequest =
        when (parcel.readInt()) {
            REQUEST_FILE -> {
                RootOperationRequest.FileRead(
                    scopeRoot = requireNotNull(parcel.readString()),
                    path = requireNotNull(parcel.readString()),
                    offset = parcel.readLong(),
                    maxBytes = parcel.readInt(),
                )
            }

            REQUEST_PACKAGE -> {
                RootOperationRequest.PackageInfo(requireNotNull(parcel.readString()))
            }

            REQUEST_PROCESSES -> {
                RootOperationRequest.ProcessList(parcel.readInt())
            }

            REQUEST_LOGS -> {
                RootOperationRequest.LogRead(parcel.readInt(), requireNotNull(parcel.readString()))
            }

            else -> {
                throw IllegalArgumentException("ROOT_REQUEST_UNKNOWN")
            }
        }

    fun writeResult(
        parcel: Parcel,
        result: RootOperationResult,
    ) {
        when (result) {
            is RootOperationResult.File -> {
                parcel.writeInt(RESULT_FILE)
                parcel.writeByteArray(result.chunk.bytes)
                parcel.writeLong(result.chunk.offset)
                parcel.writeLong(result.chunk.sizeBytes)
                parcel.writeInt(if (result.chunk.eof) 1 else 0)
            }

            is RootOperationResult.Package -> {
                parcel.writeInt(RESULT_PACKAGE)
                parcel.writeString(result.record.packageName)
                parcel.writeInt(result.record.uid)
                parcel.writeString(result.record.sourceDir)
                parcel.writeString(result.record.versionName)
            }

            is RootOperationResult.Processes -> {
                parcel.writeInt(RESULT_PROCESSES)
                parcel.writeInt(result.records.size)
                result.records.forEach { record ->
                    parcel.writeInt(record.pid)
                    parcel.writeInt(record.uid)
                    parcel.writeString(record.name)
                }
            }

            is RootOperationResult.Logs -> {
                parcel.writeInt(RESULT_LOGS)
                parcel.writeStringList(result.lines)
            }

            is RootOperationResult.Failed -> {
                parcel.writeInt(RESULT_FAILED)
                parcel.writeString(result.code)
            }
        }
    }

    private fun writeRequest(
        parcel: Parcel,
        request: RootOperationRequest,
    ) {
        when (request) {
            is RootOperationRequest.FileRead -> {
                parcel.writeInt(REQUEST_FILE)
                parcel.writeString(request.scopeRoot)
                parcel.writeString(request.path)
                parcel.writeLong(request.offset)
                parcel.writeInt(request.maxBytes)
            }

            is RootOperationRequest.PackageInfo -> {
                parcel.writeInt(REQUEST_PACKAGE)
                parcel.writeString(request.packageName)
            }

            is RootOperationRequest.ProcessList -> {
                parcel.writeInt(REQUEST_PROCESSES)
                parcel.writeInt(request.limit)
            }

            is RootOperationRequest.LogRead -> {
                parcel.writeInt(REQUEST_LOGS)
                parcel.writeInt(request.maxLines)
                parcel.writeString(request.minPriority)
            }
        }
    }

    private fun readResult(parcel: Parcel): RootOperationResult =
        when (parcel.readInt()) {
            RESULT_FILE -> {
                RootOperationResult.File(
                    RootFileChunk(
                        bytes = requireNotNull(parcel.createByteArray()),
                        offset = parcel.readLong(),
                        sizeBytes = parcel.readLong(),
                        eof = parcel.readInt() == 1,
                    ),
                )
            }

            RESULT_PACKAGE -> {
                RootOperationResult.Package(
                    RootPackageRecord(
                        requireNotNull(parcel.readString()),
                        parcel.readInt(),
                        requireNotNull(parcel.readString()),
                        parcel.readString(),
                    ),
                )
            }

            RESULT_PROCESSES -> {
                RootOperationResult.Processes(
                    List(parcel.readInt()) {
                        RootProcessRecord(parcel.readInt(), parcel.readInt(), requireNotNull(parcel.readString()))
                    },
                )
            }

            RESULT_LOGS -> {
                RootOperationResult.Logs(mutableListOf<String>().also(parcel::readStringList))
            }

            RESULT_FAILED -> {
                RootOperationResult.Failed(requireNotNull(parcel.readString()))
            }

            else -> {
                RootOperationResult.Failed("ROOT_RESPONSE_UNKNOWN")
            }
        }

    private const val REQUEST_FILE = 1
    private const val REQUEST_PACKAGE = 2
    private const val REQUEST_PROCESSES = 3
    private const val REQUEST_LOGS = 4
    private const val RESULT_FILE = 11
    private const val RESULT_PACKAGE = 12
    private const val RESULT_PROCESSES = 13
    private const val RESULT_LOGS = 14
    private const val RESULT_FAILED = 15
}
