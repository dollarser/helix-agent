package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import java.util.UUID

internal data class MobileUseDialogRequest(
    val id: String,
    val sessionId: String,
    val originEntryId: String,
    val repairRoute: String = "",
    val repairEntryId: String = "",
)

/** UI navigation identity only: never stores an enable intent or any grant. */
internal class MobileUseDialogState(
    initial: MobileUseDialogRequest? = null,
) {
    var request by mutableStateOf(initial)
        private set

    fun open(
        sessionId: String,
        originEntryId: String,
    ) {
        require(sessionId.isNotBlank() && originEntryId.isNotBlank())
        request = MobileUseDialogRequest(UUID.randomUUID().toString(), sessionId, originEntryId)
    }

    fun dismiss() {
        request = null
    }

    fun repair(
        route: String,
        entryId: String,
    ) {
        request = request?.copy(repairRoute = route, repairEntryId = entryId)
    }

    fun reconcile(
        entryId: String,
        sessionId: String?,
        repairPresent: Boolean,
    ) {
        val current = request ?: return
        if (sessionId != current.sessionId || (entryId != current.originEntryId && !repairPresent)) dismiss()
    }

    fun matches(
        id: String,
        entryId: String?,
        sessionId: String?,
    ): Boolean = request?.let { it.id == id && it.originEntryId == entryId && it.sessionId == sessionId } == true

    companion object {
        val Saver =
            listSaver<MobileUseDialogState, String>(
                save = { state ->
                    state.request
                        ?.let {
                            listOf(
                                it.id,
                                it.sessionId,
                                it.originEntryId,
                                it.repairRoute,
                                it.repairEntryId,
                            )
                        }.orEmpty()
                },
                restore = { fields ->
                    when {
                        fields.isEmpty() -> {
                            MobileUseDialogState()
                        }

                        fields.size == 5 && fields.take(3).all { it.isNotBlank() } -> {
                            MobileUseDialogState(
                                MobileUseDialogRequest(fields[0], fields[1], fields[2], fields[3], fields[4]),
                            )
                        }

                        else -> {
                            null
                        }
                    }
                },
            )
    }
}
