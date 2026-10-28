package com.helix.app.infrastructure.agent

import android.util.Log
import com.helix.core.agent.runtime.TurnDiagnostics
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.model.Clock
import com.helix.core.storage.HelixStorage

/** Android composition only; lifecycle decisions and live ownership are in Core. */
internal fun createRoomTurnEngine(
    storage: HelixStorage,
    clock: Clock,
    idGenerator: () -> String,
): TurnEngine =
    TurnEngine(
        RoomTurnLifecycleStore(storage, clock, idGenerator),
        TurnDiagnostics { message, failure ->
            if (failure == null) Log.i("TurnEngine", message) else Log.e("TurnEngine", message, failure)
        },
    )
