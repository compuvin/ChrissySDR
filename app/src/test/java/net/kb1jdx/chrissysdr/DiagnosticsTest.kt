package com.kb1jdx.chrissysdr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun snapshotIncludesMeasuredValuesAndUnreportedCounters() {
        val text = diagnosticsText(RadioUiState(
            host = "192.168.1.116",
            port = "55132",
            selectedDeviceLabel = "Test SDR",
            rxStreamFormat = "CS16",
            rxRequestedSampleRateHz = 48_000.0,
            appliedSampleRateHz = 48_000.0,
            rxAppliedHardwareBandwidthHz = 20_000.0,
            rxStreamRateHz = 47_950.0,
            rxSequenceGaps = 2,
            observedRxOverflows = 1,
            recentErrors = listOf("RX failed: stream status -4"),
        ), "0.5.2-dev.9")
        assertTrue(text.contains("192.168.1.116:55132"))
        assertTrue(text.contains("Stream format: CS16"))
        assertTrue(text.contains("Applied hardware bandwidth: 20"))
        assertTrue(text.contains("47950.0 samples/s"))
        assertTrue(text.contains("RX sequence gaps: 2"))
        assertTrue(text.contains("Observed RX overflow errors: 1"))
        assertTrue(text.contains("Other hardware underrun/overrun counters: Not reported"))
        assertTrue(text.contains("RX failed: stream status -4"))
    }

    @Test fun streamCodesAreClassifiedNarrowly() {
        assertTrue(IllegalStateException("SoapyRemote RX stream status -4").isSoapyStreamCode(-4))
        assertTrue(IllegalStateException("SoapyRemote TX stream status -7").isSoapyStreamCode(-7))
        assertFalse(IllegalStateException("sample rate -4").isSoapyStreamCode(-4))
        assertFalse(IllegalStateException("stream status -42").isSoapyStreamCode(-4))
    }
}
