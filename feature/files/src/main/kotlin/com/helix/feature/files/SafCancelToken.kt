package com.helix.feature.files

/**
 * Cooperative cancel token for the long-running SAF streaming operations (HXA-044 大流取消).
 *
 * Deliberately the minimal mirror of the tool framework's `CancelSignal` instead of a
 * dependency on it: the platform layer must not depend on the tool layer, and a caller adapts
 * its `CancelSignal` in one line (`token::isCancelled`).
 */
fun interface SafCancelToken {
    fun isCancelled(): Boolean
}
