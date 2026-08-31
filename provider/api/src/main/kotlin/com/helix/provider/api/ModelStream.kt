package com.helix.provider.api

import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest

/**
 * The request-side seam of a protocol island (HXA-025): turns the internal
 * [ModelRequest] into the vendor request body. Implemented by the protocol
 * encoders (HXA-022/023/024).
 */
public fun interface RequestEncoder {
    public fun encode(request: ModelRequest): String
}

/**
 * The stream-side seam of a protocol island (HXA-025): consumes raw HTTP body
 * chunks and produces the internal [ModelEvent] sequence. Implemented by the
 * protocol decoders (HXA-022/023/024). One instance per HTTP stream; the
 * implementation enforces the HXA-021 stream contract (terminal uniqueness,
 * stop-after-failure) and emits the terminal [ModelEvent.Error] for both
 * protocol violations and no-termination.
 */
public interface StreamDecoder {
    /** Feed one raw body chunk; returns the internal events it produced. */
    public fun feed(chunk: ByteArray): List<ModelEvent>

    /** The stream ended; flushes the tail and enforces the terminal guard. */
    public fun finish(): List<ModelEvent>
}
