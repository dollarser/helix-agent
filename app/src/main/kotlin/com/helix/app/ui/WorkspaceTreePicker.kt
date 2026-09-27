package com.helix.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Preserve the OS-granted flags; a tree URI alone does not prove persistent write access. */
internal class WorkspaceTreePicker : ActivityResultContract<Uri?, Intent?>() {
    override fun createIntent(
        context: Context,
        input: Uri?,
    ): Intent = ActivityResultContracts.OpenDocumentTree().createIntent(context, input)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Intent? = intent?.takeIf { resultCode == Activity.RESULT_OK && it.data != null }
}
