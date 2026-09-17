package com.helix.app.runcontrol

/** Stable reasons shared by the engine, durable Goal settlement and continuation UI. */
object BudgetStopReasons {
    val capacity = setOf("INPUT_TOKEN_LIMIT", "CONTEXT_WINDOW_LIMIT", "CONTEXT_MESSAGE_LIMIT")
    val turn =
        capacity +
            setOf(
                "OUTPUT_TOKEN_LIMIT",
                "TURN_TOTAL_TOKEN_LIMIT",
                "TOKEN_BUDGET_LIMIT",
                "MODEL_CALL_LIMIT",
                "TOOL_STEP_LIMIT",
            )
}
