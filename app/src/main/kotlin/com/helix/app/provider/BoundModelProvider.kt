package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.provider.api.ModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** A Turn uses one exact target for ordinary requests, compaction and tool backfill. */
internal class BoundModelProvider(
    private val delegate: ModelProvider,
    private val model: String,
    private val accountCurrent: suspend () -> Boolean,
) : ModelProvider by delegate {
    init {
        require(delegate.descriptor.model == model)
    }

    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            when {
                request.model != model -> emit(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
                !accountCurrent() -> emit(ModelEvent.Error(ModelErrorCode.AUTH, false))
                else -> emitAll(delegate.stream(request))
            }
        }
}
