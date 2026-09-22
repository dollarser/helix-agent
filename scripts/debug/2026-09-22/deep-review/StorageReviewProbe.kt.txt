package com.helix.review

import com.helix.core.model.TurnState
import com.helix.core.storage.dao.TurnDao
import com.helix.core.storage.entity.TurnEntity
import com.helix.core.storage.repository.TurnRepository
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageReviewProbe {
    @Test
    fun staleSnapshotCanOverwriteTerminalState() {
        var row = TurnEntity("turn", "session", "RECEIVING_MODEL", 1, 1, null, null)
        val dao = Proxy.newProxyInstance(TurnDao::class.java.classLoader, arrayOf(TurnDao::class.java)) { _, method, args ->
            when (method.name) {
                "byId" -> row
                "updateState" -> {
                    row = row.copy(state = args[1] as String, stepCount = args[2] as Int,
                        endedAt = args[3] as Long?, errorCode = args[4] as String?)
                    null
                }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as TurnDao
        val repository = TurnRepository(dao)
        val stale = repository.resolve("turn")
        repository.updateState(row, TurnState.COMPLETED, 1, 2, null)
        repository.updateState(stale, TurnState.CANCELLING, 1, null, null)
        assertEquals("CANCELLING", row.state)
        assertEquals(null, row.endedAt)
        println("REPRODUCED stale snapshot: COMPLETED -> ${row.state}, endedAt=${row.endedAt}")
    }
}
