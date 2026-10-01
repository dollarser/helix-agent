package com.helix.core.agent

/** A capacity failure is recoverable and never means the archived conversation was deleted. */
class ContextCapacityException(
    val code: String,
) : IllegalArgumentException(code)
