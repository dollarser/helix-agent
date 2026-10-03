package com.helix.app.plugin

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.helix.app.MainActivity
import com.helix.app.chat.ChatService
import com.helix.extensions.plugin.PluginTaskHost
import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.extensions.plugin.PluginTaskSnapshot

/** Shared native-plugin task controls; no knowledge of Mobile Use, windows, or device actions. */
internal class PluginTaskHostAdapter(
    private val context: Context,
    private val chat: () -> ChatService,
) : PluginTaskHost {
    private fun task(identity: PluginTaskIdentity) =
        chat().backgroundTasks.value.singleOrNull {
            it.id == identity.turnId && it.sessionId == identity.conversationId
        }

    override fun snapshot(task: PluginTaskIdentity): PluginTaskSnapshot? =
        task(task)?.let { PluginTaskSnapshot(it.state, it.awaitingApproval) }

    override fun requestStop(task: PluginTaskIdentity) {
        val current = task(task)?.takeIf { it.running } ?: return
        chat().stopTask(current.id, pause = current.goalId != null)
    }

    override fun openConversation(task: PluginTaskIdentity) {
        Handler(Looper.getMainLooper()).post {
            if (task(task) != null && chat().sessions.value.any { it.id == task.conversationId }) {
                chat().openSession(task.conversationId)
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
