package com.helix.app.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech

/** System capability inspection only: never records, synthesizes or contacts a backend. */
class SystemVoiceSupport(
    private val context: Context,
) {
    data class Status(
        val inputActivity: Boolean,
        val recognitionService: Boolean,
        val ttsEngines: Int,
    )

    fun inspect(): Status =
        Status(
            SpeechRecognitionLauncher().isAvailable(context),
            SpeechRecognizer.isRecognitionAvailable(context),
            context.packageManager
                .queryIntentServices(
                    Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                    PackageManager.MATCH_ALL,
                ).map { it.serviceInfo.packageName }
                .distinct()
                .size,
        )

    fun openSettings(synthesis: Boolean): Boolean {
        val actions =
            if (synthesis) {
                listOf(
                    "com.android.settings.TTS_SETTINGS",
                    Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    Settings.ACTION_SETTINGS,
                )
            } else {
                listOf(
                    Settings.ACTION_VOICE_INPUT_SETTINGS,
                    Settings.ACTION_INPUT_METHOD_SETTINGS,
                    Settings.ACTION_SETTINGS,
                )
            }
        for (action in actions) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // OEMs may omit the dedicated page; try the broader system page.
            } catch (_: SecurityException) {
                // A present but restricted OEM page is not a usable settings entry.
            }
        }
        return false
    }
}
