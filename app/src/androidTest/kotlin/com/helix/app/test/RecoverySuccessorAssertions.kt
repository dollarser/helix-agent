package com.helix.app.test

import com.helix.app.engine.TurnRuntimeRecordCodec
import com.helix.core.model.AgentMode
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Process recovery may inspect once in Plan mode; it must never replay the original turn. */
internal fun assertReadOnlyRecoverySuccessors(
    storage: HelixStorage,
    parent: TurnEntity,
) {
    val successors = storage.turns.listBySession(parent.sessionId).filter { it.id != parent.id }
    assertTrue("At most one recovery inspection is admitted", successors.size <= 1)
    successors.forEach { child ->
        assertEquals("auto-recovery:${parent.id}", child.clientRequestId)
        assertEquals(parent.id, child.recoveryFromTurnId)
        val snapshot = TurnRuntimeRecordCodec.decode(storage.turnRuntimeRecords.resolve(child.id))
        assertEquals(AgentMode.PLAN, snapshot.control.mode)
    }
}
