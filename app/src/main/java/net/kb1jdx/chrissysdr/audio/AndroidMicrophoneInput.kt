package com.kb1jdx.chrissysdr.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AndroidMicrophoneInput(val sampleRate: Int = 48_000) : AutoCloseable {
    private val recorder: AudioRecord

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0) { "Android microphone input is unavailable ($minimumBuffer)" }
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBuffer * 2, sampleRate / 5 * 2))
            .build()
    }

    init {
        recorder = createRecorder()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "Android microphone failed to initialize"
        }
    }

    fun start() {
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            "Android microphone did not start"
        }
    }

    fun read(samples: ShortArray): Int {
        val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
        check(count >= 0) { "Android microphone read failed ($count)" }
        return count
    }

    override fun close() {
        runCatching { recorder.stop() }
        recorder.release()
    }
}
