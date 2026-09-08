package com.helix.feature.browser

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import java.util.concurrent.CountDownLatch

/** Actual OS-bound service in the test APK; all values are synthetic. */
class FixtureAutofillService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val nodes = nodes(request.fillContexts.last().structure)

        fun fieldId(name: String) =
            nodes
                .firstOrNull { node ->
                    node.htmlInfo?.attributes?.any { it.first == "name" && it.second == name } == true
                }?.autofillId
        val id = fieldId("username")
        val passwordId = fieldId("password")
        if (id == null || passwordId == null) {
            callback.onSuccess(null)
            return
        }
        val presentation = RemoteViews("android", android.R.layout.simple_list_item_1)
        presentation.setTextViewText(android.R.id.text1, "Helix fixture account")
        callback.onSuccess(
            FillResponse
                .Builder()
                .addDataset(
                    Dataset
                        .Builder(presentation)
                        .setValue(id, AutofillValue.forText("fixture-user"))
                        .setValue(passwordId, AutofillValue.forText("fixture-password"))
                        .build(),
                ).setSaveInfo(
                    SaveInfo
                        .Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(id, passwordId))
                        .build(),
                ).build(),
        )
        filled.countDown()
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback,
    ) {
        savedValues =
            nodes(request.fillContexts.last().structure).mapNotNull {
                it.autofillValue
                    ?.takeIf { v ->
                        v.isText
                    }?.textValue
                    ?.toString()
            }
        callback.onSuccess()
        saved.countDown()
    }

    private fun nodes(structure: AssistStructure): List<AssistStructure.ViewNode> {
        val result = mutableListOf<AssistStructure.ViewNode>()

        fun visit(node: AssistStructure.ViewNode) {
            result += node
            for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
        return result
    }

    companion object {
        @Volatile var filled = CountDownLatch(1)

        @Volatile var saved = CountDownLatch(1)

        @Volatile var savedValues: List<String> = emptyList()
    }
}
