package com.helix.app.provider

/** Presentation only. Selection and execution continue using the existing persisted admission rules. */
enum class ProviderSetupStep {
    ACCOUNT,
    CONNECTION,
    MODELS,
    READY,
    ;

    companion object {
        fun forRow(row: ProviderRowUi): ProviderSetupStep =
            when {
                row.managedExternally && row.accountState?.ready != true -> ACCOUNT
                !row.chatSelectable -> CONNECTION
                row.conversationModels.none(row::modelSelectable) -> MODELS
                else -> READY
            }
    }
}
