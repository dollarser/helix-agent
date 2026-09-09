package com.helix.runtime.quickjs

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

internal class JsTransportPreparation(
    private val context: Context,
) {
    data class Transport(
        val sourcePfd: ParcelFileDescriptor?,
        val inputPfd: ParcelFileDescriptor?,
        val outputPfd: ParcelFileDescriptor?,
        val inlineSource: ByteArray,
        val inlineInput: ByteArray,
    )

    /**
     * Transport assembly (doc 03 §3.1): payloads above the inline parcel cap move to
     * read-only PFDs over app-private temp files; [JsExecuteParams.outputFile] becomes
     * the caller-writable output PFD. All created resources are registered in
     * [tempFiles]/[pfdHolders] so the `finally` path releases them unconditionally.
     */
    fun prepareTransport(
        params: JsExecuteParams,
        sourceBytes: ByteArray,
        inputBytes: ByteArray?,
        tempFiles: MutableList<File>,
        pfdHolders: MutableList<ParcelFileDescriptor>,
    ): Transport {
        val sourcePfd: ParcelFileDescriptor?
        val inlineSource: ByteArray
        if (sourceBytes.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
            val tmp = materializeTemp(params.executionId, "source", sourceBytes, tempFiles)
            sourcePfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            pfdHolders += sourcePfd
            inlineSource = ByteArray(0)
        } else {
            sourcePfd = null
            inlineSource = sourceBytes
        }
        val inputPfd: ParcelFileDescriptor?
        val inlineInput: ByteArray
        if (inputBytes != null && inputBytes.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
            val tmp = materializeTemp(params.executionId, "input", inputBytes, tempFiles)
            inputPfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            pfdHolders += inputPfd
            inlineInput = ByteArray(0)
        } else {
            inputPfd = null
            inlineInput = inputBytes ?: ByteArray(0)
        }
        var outputPfd: ParcelFileDescriptor? = null
        if (params.outputFile != null) {
            val target = params.outputFile.absoluteFile
            target.parentFile?.mkdirs()
            outputPfd =
                ParcelFileDescriptor.open(
                    target,
                    ParcelFileDescriptor.MODE_WRITE_ONLY or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE,
                )
            pfdHolders += outputPfd
        }
        return Transport(sourcePfd, inputPfd, outputPfd, inlineSource, inlineInput)
    }

    private fun materializeTemp(
        executionId: String,
        tag: String,
        bytes: ByteArray,
        tempFiles: MutableList<File>,
    ): File {
        // The execution ID is internal (the tool generates UUIDs), but keep only path-safe
        // characters so an arbitrary caller-supplied ID cannot redirect the temp file
        // outside the cache directory.
        val safeId = executionId.take(128).replace(pathUnsafeChars, "_")
        val tmp = File(context.cacheDir, "js-exec-$safeId-$tag.tmp")
        tmp.writeBytes(bytes)
        tempFiles += tmp
        return tmp
    }

    private val pathUnsafeChars = Regex("[^A-Za-z0-9._-]")
}
