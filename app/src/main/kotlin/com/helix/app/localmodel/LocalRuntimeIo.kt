package com.helix.app.localmodel

import android.os.ParcelFileDescriptor

internal fun readReply(descriptor: ParcelFileDescriptor): ByteArray =
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(result.size() + count <= LocalRuntimeCodec.MAX_BYTES)
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    }
