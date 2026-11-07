package com.helix.app.plugin

import android.content.Context
import com.helix.app.R
import com.helix.app.approval.ApprovalUiMapper
import com.helix.extensions.plugin.PluginTaskApproval
import com.helix.feature.conversation.presentation.ApprovalCardUi
import com.helix.feature.conversation.presentation.approvalProfileLabel
import com.helix.feature.conversation.R as ConversationR

/** The same immutable request-time card facts as chat, localized for trusted native surfaces. */
internal object NativeApprovalDisplay {
    @Suppress("SpreadOperator") // Android's localized format resources require the complete argument array.
    fun map(
        context: Context,
        card: ApprovalCardUi,
    ): PluginTaskApproval {
        fun label(
            res: Int,
            args: List<String> = emptyList(),
        ) = context.getString(res, *args.toTypedArray())

        val details = details(context, card)
        return PluginTaskApproval(
            card.approvalId,
            label(
                com.helix.feature.conversation.presentation.ToolPurpose
                    .actionRes(card.toolName),
            ),
            com.helix.feature.conversation.presentation.ApprovalArgumentPreview
                .text(card.arguments) +
                card.networkOrigin?.let { "\n$it" }.orEmpty(),
            details,
        )
    }

    @Suppress("SpreadOperator")
    private fun details(
        context: Context,
        card: ApprovalCardUi,
    ): String {
        fun label(
            res: Int,
            args: List<String> = emptyList(),
        ) = context.getString(res, *args.toTypedArray())

        fun line(
            res: Int,
            value: String,
        ) = context.getString(ConversationR.string.approval_field_line, label(res), value)
        return listOfNotNull(
            line(ConversationR.string.approval_source, label(card.sourceRes, card.sourceArgs)),
            line(ConversationR.string.approval_target, label(card.targetRes)),
            line(
                ConversationR.string.approval_scope,
                if (card.scope ==
                    "unscoped"
                ) {
                    label(ConversationR.string.approval_scope_unscoped)
                } else {
                    card.scope
                },
            ),
            line(
                ConversationR.string.approval_operation,
                label(card.operationRes, card.operationArgs.map { label(it) }),
            ),
            line(ConversationR.string.approval_profile_label, label(approvalProfileLabel(card.profile))),
            line(
                ConversationR.string.approval_provider_label,
                card.providerMcpId ?: label(ConversationR.string.approval_builtin_no_provider_mcp),
            ),
            line(ConversationR.string.approval_network_origin, card.networkOrigin ?: label(ApprovalCardUi.NO_EGRESS)),
            line(ConversationR.string.approval_data_residence, card.residence ?: label(ApprovalCardUi.NO_EGRESS)),
            line(ConversationR.string.approval_data_category, label(card.dataCategoryRes)),
            card.boundedRule?.let {
                label(
                    ConversationR.string.approval_bounded_rule,
                    listOf(label(it.displayRes, it.displayArgs)),
                )
            },
            line(ConversationR.string.approval_arguments, card.arguments),
            card.codeOrCommand?.let { line(ConversationR.string.approval_code_command, it) },
            card.codeExecution?.let {
                listOf(
                    line(ConversationR.string.approval_code_sha256, it.codeSha256Short),
                    line(ConversationR.string.approval_input_source, label(it.inputSourceRes, it.inputSourceArgs)),
                    line(ConversationR.string.approval_execution_limits, label(it.limitsRes, it.limitsArgs)),
                    line(
                        ConversationR.string.approval_online,
                        label(if (it.online) ConversationR.string.approval_yes else ConversationR.string.approval_no),
                    ),
                    it.code,
                ).joinToString("\n")
            },
            line(ConversationR.string.approval_verifier, label(card.verifierRes)),
            label(ConversationR.string.approval_no_permanent_allow),
            card.confirmationDetail,
            card.expectedImpact,
        ).joinToString("\n")
    }
}
