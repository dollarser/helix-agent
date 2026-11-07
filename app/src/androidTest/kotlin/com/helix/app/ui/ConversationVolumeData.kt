package com.helix.app.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnBudgetBounds
import com.helix.core.model.AgentMode
import com.helix.core.model.ToolCallState
import com.helix.feature.conversation.presentation.ChatImageRef
import com.helix.feature.conversation.presentation.ChatImageUi
import com.helix.feature.conversation.presentation.ChatScreen
import com.helix.feature.conversation.presentation.ChatScreenState
import com.helix.feature.conversation.presentation.ConversationIntents
import com.helix.feature.conversation.presentation.MessageUi
import com.helix.feature.conversation.presentation.ToolTimelinePosition
import com.helix.feature.conversation.presentation.ToolTimelineRow
import com.helix.feature.conversation.presentation.TurnUi
import java.io.ByteArrayOutputStream

/** Fixed synthetic metadata; file availability/preview is validated separately by the R8 fixture. */
internal class ConversationVolumeData {
    private val pngs =
        (0 until 25).map { index ->
            ByteArrayOutputStream()
                .also { out ->
                    val image = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                    image.eraseColor(android.graphics.Color.rgb(index * 9, index * 37 % 256, index * 59 % 256))
                    image.compress(Bitmap.CompressFormat.PNG, 100, out)
                    image.recycle()
                }.toByteArray()
        }
    private val messages =
        (0 until 200).flatMap { turn ->
            listOf(
                MessageUi("u-$turn", "user", "Synthetic request $turn", "t-$turn", turn * 3L),
                MessageUi(
                    "a-$turn",
                    "assistant",
                    "Synthetic answer $turn. ".repeat(20),
                    "t-$turn",
                    turn * 3L + 2,
                    images =
                        if (turn % 8 == 0) {
                            listOf(ChatImageUi(ChatImageRef("volume", "a-$turn", "image-$turn"), "synthetic.png"))
                        } else {
                            emptyList()
                        },
                ),
            )
        }
    private val tools =
        (0 until 200).flatMap { turn ->
            (0 until 5).map { index ->
                ToolTimelineRow(
                    "t-$turn",
                    "c-$turn-$index",
                    "read",
                    "{\"path\":\"synthetic-$turn-$index.txt\"}",
                    "Completed",
                    "Synthetic result",
                    null,
                    position = ToolTimelinePosition(turn * 3L + 1, index),
                    state = ToolCallState.COMPLETED,
                )
            }
        }

    // A completed latest Turn keeps its answer; artifact management stays outside the timeline.
    val screen =
        ChatScreenState(emptyList(), "volume", null, messages, tools, null, null, null, null).copy(
            activeTurn =
                TurnUi(
                    "t-199",
                    com.helix.core.model.TurnState.COMPLETED,
                    null,
                    null,
                    false,
                ),
        )
    val intents =
        ConversationIntents(
            {},
            {},
            onDismissBlocked = {},
            onApproveApproval = {},
            onDenyApproval = {},
            onStageAttachment = {},
            onRemoveAttachment = {},
            onSetMode = {},
            onPreviewImage = { pngs[it.attachmentId.removePrefix("image-").toInt() / 8] },
        )

    @Composable
    @Suppress("FunctionName")
    fun Content() {
        MaterialTheme {
            ChatScreen(
                screen,
                RunControlConfig(AgentMode.CHAT, false, TurnBudgetBounds.DEFAULT),
                "",
                {},
                emptyList(),
                intents,
            )
        }
    }
}
