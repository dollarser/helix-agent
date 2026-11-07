package com.helix.core.storage.repository

import com.helix.core.storage.dao.TurnRuntimeRecordDao
import com.helix.core.storage.entity.TurnRuntimeRecordEntity

/**
 * Single write surface for TurnEngine runtime snapshots.
 *
 * The start snapshot is immutable. Only the three budget/checkpoint counters may change and every
 * change is CAS-guarded so a stale/replayed owner cannot silently refund or double-spend budget.
 */
class TurnRuntimeRecordRepository(
    private val dao: TurnRuntimeRecordDao,
) {
    fun create(record: TurnRuntimeRecordEntity): TurnRuntimeRecordEntity {
        validateStart(record)
        dao.insert(record)
        return record
    }

    fun find(turnId: String): TurnRuntimeRecordEntity? {
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        return dao.byTurn(turnId)
    }

    fun resolve(turnId: String): TurnRuntimeRecordEntity =
        requireNotNull(find(turnId)) { "turn runtime record not found: $turnId" }

    fun checkpointModelAdmission(
        turnId: String,
        expectedConsumedModelCalls: Int,
    ): TurnRuntimeRecordEntity {
        require(expectedConsumedModelCalls >= 0) { "expected model calls must be non-negative" }
        check(dao.incrementModelCalls(turnId, expectedConsumedModelCalls) == 1) {
            "TURN_RUNTIME_CHECKPOINT_CONFLICT: model calls for $turnId"
        }
        return resolve(turnId)
    }

    fun checkpointTokens(
        turnId: String,
        expectedConsumedTokens: Long,
        consumedTokens: Long,
    ): TurnRuntimeRecordEntity {
        require(expectedConsumedTokens >= 0) { "expected tokens must be non-negative" }
        require(consumedTokens >= expectedConsumedTokens) {
            "token checkpoint must be monotonic: $expectedConsumedTokens -> $consumedTokens"
        }
        check(dao.compareAndSetTokens(turnId, expectedConsumedTokens, consumedTokens) == 1) {
            "TURN_RUNTIME_CHECKPOINT_CONFLICT: tokens for $turnId"
        }
        return resolve(turnId)
    }

    fun checkpointToolRound(
        turnId: String,
        expectedAdmittedToolRounds: Int,
    ): TurnRuntimeRecordEntity {
        require(expectedAdmittedToolRounds >= 0) { "expected tool rounds must be non-negative" }
        check(dao.incrementToolRounds(turnId, expectedAdmittedToolRounds) == 1) {
            "TURN_RUNTIME_CHECKPOINT_CONFLICT: tool rounds for $turnId"
        }
        return resolve(turnId)
    }

    private fun validateStart(record: TurnRuntimeRecordEntity) {
        require(record.turnId.isNotBlank()) { "turnId must not be blank" }
        require(record.version == CURRENT_VERSION) { "unsupported turn runtime record version: ${record.version}" }
        require(record.providerId.isNotBlank()) { "providerId must not be blank" }
        require(record.modelId.isNotBlank()) { "modelId must not be blank" }
        require(record.providerSnapshot.isNotBlank()) { "providerSnapshot must not be blank" }
        require(record.mode.isNotBlank()) { "mode must not be blank" }
        require(record.budgetsJson.isNotBlank()) { "budgetsJson must not be blank" }
        require(record.reasoning.isNotBlank()) { "reasoning must not be blank" }
        require(record.goalBudgetsJson.isNotBlank()) { "goalBudgetsJson must not be blank" }
        require(record.consumedModelCalls == 0) { "new Turn must start with zero consumed model calls" }
        require(record.consumedTokens == 0L) { "new Turn must start with zero consumed tokens" }
        require(record.admittedToolRounds == 0) { "new Turn must start with zero admitted tool rounds" }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
