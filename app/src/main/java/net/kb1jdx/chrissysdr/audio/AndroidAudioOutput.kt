package com.kb1jdx.chrissysdr.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AndroidAudioOutput(val sampleRate: Int) : AutoCloseable {
    private val track: AudioTrack

    init {
        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "Android audio output is unavailable ($minimumBuffer)" }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBuffer * 2, sampleRate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) {
            "Android audio output failed to initialize"
        }
        track.play()
    }

    fun write(samples: ShortArray, count: Int) {
        val written = track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
        check(written >= 0) { "Android audio write failed ($written)" }
    }

    fun setVolume(volume: Float) {
        check(track.setVolume(volume.coerceIn(0f, 1f)) >= 0) {
            "Android audio volume could not be changed"
        }
    }

    override fun close() {
        runCatching { track.pause(); track.flush(); track.stop() }
        track.release()
    }
}
