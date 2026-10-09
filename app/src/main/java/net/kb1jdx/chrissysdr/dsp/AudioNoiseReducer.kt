package com.kb1jdx.chrissysdr.dsp

internal interface AudioNoiseReducer : AutoCloseable {
    fun processInPlace(audio: ShortArray)
    override fun close() {}
}
