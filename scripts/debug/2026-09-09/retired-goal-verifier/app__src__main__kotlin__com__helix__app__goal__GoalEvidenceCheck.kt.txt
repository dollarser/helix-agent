package com.helix.app.goal

import com.helix.core.storage.HelixStorage
import java.io.IOException

/**
 * Validation failures leave the read transaction normally before being rethrown. Otherwise
 * Room's nested transaction marks the caller's terminal settlement rollback-only even when
 * the caller correctly records invalid evidence. Cache writers must validate before insertion;
 * database and programming errors still escape the transaction and roll it back.
 */
internal fun <T : Any> goalEvidenceCheck(
    storage: HelixStorage,
    block: () -> T,
): T {
    var value: T? = null
    var rejected: Exception? = null
    storage.withTransaction {
        try {
            checkGoalEvidenceReadActive()
            value = block()
            checkGoalEvidenceReadActive()
        } catch (failure: IllegalArgumentException) {
            rejected = failure
        } catch (failure: IOException) {
            rejected = failure
        }
    }
    rejected?.let { throw it }
    return checkNotNull(value)
}
