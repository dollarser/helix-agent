package com.helix.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/** Bounded PCM source. stop() must tolerate a concurrent read and repeated calls. */
interface PcmCapture : AutoCloseable {
    fun read(samples: ShortArray): Int

    fun stop()
}

@SuppressLint("MissingPermission") // Foreground permission UI and OS enforce access.
@Suppress("TooGenericExceptionCaught") // Always release native resources before rethrowing construction failures.
internal class MicrophoneCapture(
    context: Context,
    onInterruption: () -> Unit,
) : PcmCapture {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val focus =
        AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setOnAudioFocusChangeListener { if (it < 0) onInterruption() }
            .build()
    private val recorder: AudioRecord
    private val resourceLock = Any()
    private var stopped = false
    private var closed = false

    init {
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw AudioFocusUnavailableException()
        }
        try {
            val minimum =
                AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            check(minimum > 0)
            val audio =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, 6400),
                )
            try {
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            } catch (failure: RuntimeException) {
                audio.release()
                throw failure
            }
            recorder = audio
        } catch (failure: RuntimeException) {
            manager.abandonAudioFocusRequest(focus)
            throw failure
        }
    }

    override fun read(samples: ShortArray): Int {
        check(recorder.activeRecordingConfiguration?.isClientSilenced != true)
        return recorder.read(samples, 0, samples.size)
    }

    override fun stop() =
        synchronized(resourceLock) {
            if (!stopped && !closed) {
                stopped = true
                recorder.stop()
            }
        }

    override fun close() =
        synchronized(resourceLock) {
            if (!closed) {
                try {
                    stop()
                } finally {
                    closed = true
                    try {
                        recorder.release()
                    } finally {
                        manager.abandonAudioFocusRequest(focus)
                    }
                }
            }
        }
}

internal class AudioFocusUnavailableException : IllegalStateException("Audio focus unavailable")
