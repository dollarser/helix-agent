package com.helix.app.eval

import com.helix.app.AppContainer
import com.helix.app.provider.BuiltInModel
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.feature.conversation.input.ChatSubmissionReceipt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Test-only normal admission for one owned, attachment-free synthetic request. Never grants tools. */
internal class SyntheticModelSubmission(
    private val session: String,
    private val onApproval: () -> Unit,
) {
    val receipts = JSONArray()

    @Suppress("TooGenericExceptionCaught") // Persist only failure type, then rethrow including cancellation.
    suspend fun submit(
        c: AppContainer,
        request: ChatSubmission,
    ): ChatSubmissionReceipt {
        try {
            check(request.sessionId == session && request.attachmentIds.isEmpty())
            receipts.put(JSONObject().put("stage", "refresh_catalog"))
            c.providerService.refreshModelCatalog(BuiltInModel.PROVIDER_ID)
            receipts.put(JSONObject().put("stage", "await_model_projection"))
            withTimeout(15_000) {
                while (true) {
                    val screen = c.chatService.screen.value
                    val sessionReady = screen.openSessionId == session && !screen.preparingDraft
                    val modelReady =
                        screen.modelBindingIssue == null && screen.selectedModel?.model == BuiltInModel.MODEL_ID
                    if (sessionReady && modelReady) {
                        break
                    }
                    delay(25)
                }
            }
            receipts.put(JSONObject().put("stage", "projection_ready"))
            return admitSyntheticSubmission(
                request,
                send = { c.chatService.sendSubmission(it).await() },
                confirm = {
                    val screen = c.chatService.screen.value
                    val disclosure = screen.pendingDisclosure
                    val allowed =
                        disclosure != null &&
                            syntheticDisclosureAllowed(
                                session,
                                screen.openSessionId,
                                request,
                                disclosure.providerId,
                                disclosure.origin,
                                disclosure.attachments.size,
                                disclosure.hasHighSensitive,
                            )
                    receipts.put(JSONObject().put("stage", "disclosure_check").put("allowed", allowed))
                    check(allowed) { "Synthetic disclosure scope mismatch" }
                    onApproval()
                    c.chatService.confirmSubmission(it).await()
                },
                record = { receipts.put(safeSubmissionReceipt(it)) },
            )
        } catch (failure: Throwable) {
            receipts.put(JSONObject().put("stage", "failed").put("failureType", failure.javaClass.simpleName))
            throw failure
        }
    }
}

internal suspend fun admitSyntheticSubmission(
    request: ChatSubmission,
    send: suspend (ChatSubmission) -> ChatSubmissionReceipt,
    confirm: suspend (ChatSubmission) -> ChatSubmissionReceipt,
    record: (ChatSubmissionReceipt) -> Unit,
): ChatSubmissionReceipt {
    val first = send(request)
    check(first.submission == request)
    record(first)
    if (first.outcome != ChatSubmissionOutcome.PendingConfirmation) return first
    val confirmed = confirm(request)
    check(confirmed.submission == request)
    record(confirmed)
    return confirmed
}

// Explicit independent disclosure boundaries, no implicit authorization.
@Suppress("LongParameterList", "CyclomaticComplexMethod")
internal fun syntheticDisclosureAllowed(
    session: String,
    openSession: String?,
    request: ChatSubmission,
    provider: String,
    origin: String,
    attachmentCount: Int,
    highSensitive: Boolean,
): Boolean {
    val uri = runCatching { URI(origin) }.getOrNull() ?: return false
    return request.sessionId == session && openSession == session && request.attachmentIds.isEmpty() &&
        provider == BuiltInModel.PROVIDER_ID && uri.scheme == "https" && uri.host == "api.hilex.me" &&
        uri.port in setOf(-1, 443) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.path in setOf("", "/") && attachmentCount == 0 && !highSensitive
}

private fun safeSubmissionReceipt(receipt: ChatSubmissionReceipt): JSONObject {
    val outcome = receipt.outcome
    val type =
        when (outcome) {
            is ChatSubmissionOutcome.Accepted -> "Accepted"
            is ChatSubmissionOutcome.Enqueued -> "Enqueued"
            is ChatSubmissionOutcome.Rejected -> "Rejected"
            ChatSubmissionOutcome.PendingConfirmation -> "PendingConfirmation"
        }
    val result = JSONObject().put("outcome", type)
    if (outcome is ChatSubmissionOutcome.Rejected) {
        result.put("reason", outcome.reason.takeIf { Regex("[A-Z_]{1,80}").matches(it) } ?: "NON_CODE_REJECTION")
    }
    return result
}
