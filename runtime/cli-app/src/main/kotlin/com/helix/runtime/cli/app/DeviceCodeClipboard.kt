package com.helix.runtime.cli.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

internal object DeviceCodeClipboard {
    fun copy(
        context: Context,
        label: String,
        value: String,
    ) {
        require(value.isNotBlank())
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras =
                PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}
