package com.helix.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock

interface SpeechSink : AutoCloseable {
    fun write(
        samples: FloatArray,
        keepGoing: () -> Boolean,
    )

    fun finish(keepGoing: () -> Boolean)

    fun interrupt()
}

class SpeechAudio(
    private val context: Context,
    private val volume: Float = 1f,
) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    fun focus(onLoss: () -> Unit): AutoCloseable? {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { if (it != AudioManager.AUDIOFOCUS_GAIN) onLoss() }
                .build()
        return if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AutoCloseable { manager.abandonAudioFocusRequest(request) }
        } else {
            null
        }
    }

    fun sink(sampleRate: Int): SpeechSink = Track(sampleRate, attributes, volume)

    private class Track(
        private val rate: Int,
        attributes: AudioAttributes,
        volume: Float,
    ) : SpeechSink {
        private val audioTrack =
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build(),
                ).setBufferSizeInBytes(
                    maxOf(
                        rate * 4 / 5,
                        AudioTrack.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_FLOAT,
                        ),
                    ),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        private var frames = 0L

        init {
            check(audioTrack.state == AudioTrack.STATE_INITIALIZED)
            check(volume in 0f..1f)
            audioTrack.setVolume(volume)
            audioTrack.play()
        }

        override fun write(
            samples: FloatArray,
            keepGoing: () -> Boolean,
        ) {
            var offset = 0
            while (offset < samples.size && keepGoing()) {
                val count =
                    audioTrack.write(
                        samples,
                        offset,
                        minOf(1024, samples.size - offset),
                        AudioTrack.WRITE_BLOCKING,
                    )
                if (!keepGoing()) return
                check(count > 0) { "AUDIO_OUTPUT_FAILED" }
                frames += count
                offset += count
            }
        }

        override fun finish(keepGoing: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (keepGoing() && audioTrack.playbackHeadPosition.toUInt().toLong() < frames) {
                check(SystemClock.elapsedRealtime() < deadline) { "AUDIO_OUTPUT_STALLED" }
                Thread.sleep(10)
            }
        }

        override fun interrupt() {
            try {
                audioTrack.pause()
                audioTrack.flush()
            } catch (
                _: IllegalStateException,
            ) {
                // Writer owns eventual release.
            }
        }

        override fun close() {
            audioTrack.release()
        }
    }
}
