package com.helix.app.plugin

import android.content.Context
import com.helix.app.R
import com.helix.app.approval.ApprovalCardUi
import com.helix.app.approval.ApprovalUiMapper
import com.helix.extensions.plugin.PluginTaskApproval

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

        fun line(
            res: Int,
            value: String,
        ) = context.getString(R.string.approval_field_line, label(res), value)
        val details =
            listOfNotNull(
                line(R.string.approval_source, label(card.sourceRes, card.sourceArgs)),
                line(R.string.approval_target, label(card.targetRes)),
                line(
                    R.string.approval_scope,
                    if (card.scope ==
                        "unscoped"
                    ) {
                        label(R.string.approval_scope_unscoped)
                    } else {
                        card.scope
                    },
                ),
                line(R.string.approval_operation, label(card.operationRes, card.operationArgs.map { label(it) })),
                line(R.string.approval_profile_label, label(ApprovalUiMapper.profileLabel(card.profile))),
                line(
                    R.string.approval_provider_label,
                    card.providerMcpId ?: label(R.string.approval_builtin_no_provider_mcp),
                ),
                line(R.string.approval_network_origin, card.networkOrigin ?: label(ApprovalCardUi.NO_EGRESS)),
                line(R.string.approval_data_residence, card.residence ?: label(ApprovalCardUi.NO_EGRESS)),
                line(R.string.approval_data_category, label(card.dataCategoryRes)),
                card.boundedRule?.let {
                    label(
                        R.string.approval_bounded_rule,
                        listOf(label(it.displayRes, it.displayArgs)),
                    )
                },
                line(R.string.approval_arguments, card.arguments),
                card.codeOrCommand?.let { line(R.string.approval_code_command, it) },
                card.codeExecution?.let {
                    listOf(
                        line(R.string.approval_code_sha256, it.codeSha256Short),
                        line(R.string.approval_input_source, label(it.inputSourceRes, it.inputSourceArgs)),
                        line(R.string.approval_execution_limits, label(it.limitsRes, it.limitsArgs)),
                        line(
                            R.string.approval_online,
                            label(if (it.online) R.string.approval_yes else R.string.approval_no),
                        ),
                        it.code,
                    ).joinToString("\n")
                },
                line(R.string.approval_verifier, label(card.verifierRes)),
                label(R.string.approval_no_permanent_allow),
                card.confirmationDetail,
                card.expectedImpact,
            ).joinToString("\n")
        return PluginTaskApproval(
            card.approvalId,
            card.toolName,
            label(card.operationRes, card.operationArgs.map { label(it) }) + "\n" +
                line(R.string.approval_arguments, card.arguments),
            details,
        )
    }
}
