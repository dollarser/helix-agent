package com.helix.feature.conversation.presentation

import com.helix.feature.conversation.R

/** Current binding facts, independent of whether a model is hidden from the new-selection menu. */
enum class ProviderBindingIssue(
    val messageResource: Int,
) {
    SOURCE_DELETED(R.string.chat_model_source_deleted),
    CONFIGURATION_CHANGED(R.string.chat_model_source_changed),
    MODEL_MISSING(R.string.chat_model_missing),
    MODEL_NOT_CHAT(R.string.provider_model_not_chat),
    MODEL_TASK_UNKNOWN(R.string.builtin_model_tasks_missing),
    AUTHENTICATION(R.string.chat_model_authentication),
    NETWORK(R.string.chat_model_network),
    TEST_FAILED(R.string.chat_model_test_failed),
    UNTESTED(R.string.chat_blocked_provider_untested),
}
