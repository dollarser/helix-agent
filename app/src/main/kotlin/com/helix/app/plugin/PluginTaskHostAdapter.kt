package com.helix.app.plugin

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.helix.app.MainActivity
import com.helix.app.approval.ApprovalCardUi
import com.helix.extensions.plugin.PluginTaskHost
import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.extensions.plugin.PluginTaskSnapshot

/** Shared native-plugin task controls; no knowledge of Mobile Use, windows, or device actions. */
internal class PluginTaskHostAdapter(
    private val context: Context,
    private val awaitingDataDisclosure: (String, String) -> Boolean = { _, _ -> false },
    private val access: PluginTaskAccess,
) : PluginTaskHost {
    private var displayedCards = emptyList<com.helix.app.approval.ApprovalCardUi>()
    private var displayedLocale = ""
    private var displayedApprovals = emptyList<com.helix.extensions.plugin.PluginTaskApproval>()

    @Synchronized
    private fun display(
        cards: List<com.helix.app.approval.ApprovalCardUi>,
    ): List<com.helix.extensions.plugin.PluginTaskApproval> {
        val locale =
            context.resources.configuration.locales
                .toLanguageTags()
        if (cards != displayedCards || locale != displayedLocale) {
            displayedApprovals = cards.map { NativeApprovalDisplay.map(context, it) }
            displayedCards = cards
            displayedLocale = locale
        }
        return displayedApprovals
    }

    private fun task(identity: PluginTaskIdentity) = access.task(identity)

    override val conversationVisible: Boolean get() = access.conversationVisible()

    override fun snapshot(task: PluginTaskIdentity): PluginTaskSnapshot? =
        task(task)?.let {
            val cards = if (it.running) it.approvals else emptyList()
            PluginTaskSnapshot(
                it.state,
                cards.isNotEmpty(),
                display(cards),
                it.running && awaitingDataDisclosure(task.conversationId, task.turnId),
            )
        }

    override fun reviewApproval(
        task: PluginTaskIdentity,
        approvalId: String,
        approve: Boolean,
    ) {
        task(task)?.takeIf { it.running } ?: return
        access.reviewApproval(task, approvalId, approve)
    }

    override fun requestStop(task: PluginTaskIdentity) {
        task(task)?.takeIf { it.running } ?: return
        access.requestStop(task)
    }

    override fun openConversation(task: PluginTaskIdentity) {
        Handler(Looper.getMainLooper()).post {
            if (task(task) != null && access.openConversation(task)) {
                PluginTaskNavigation.request(task)
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        ),
                )
            }
        }
    }
}

/** Only the task projection and exact-identity actions needed by a native extension surface. */
internal class PluginTaskAccess(
    val task: (PluginTaskIdentity) -> PluginTaskState?,
    val conversationVisible: () -> Boolean,
    val reviewApproval: (PluginTaskIdentity, String, Boolean) -> Unit,
    val requestStop: (PluginTaskIdentity) -> Unit,
    val openConversation: (PluginTaskIdentity) -> Boolean,
)

internal data class PluginTaskState(
    val state: com.helix.core.model.TurnState,
    val running: Boolean,
    val approvals: List<ApprovalCardUi>,
)
