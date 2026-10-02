package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioChannelCapabilities
import com.kb1jdx.chrissysdr.radio.RadioRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioProfileMatchingTest {
    private fun profile(args: Map<String, String>) = RadioProfile(
        id = "saved", name = "Test radio", host = "192.168.1.116", port = 55132,
        deviceLabel = "Test", deviceArguments = args, frequency = "10000000",
        bandwidth = "12000", sampleRateOverrideHz = null,
    )

    @Test fun exactArgumentsSelectOnlyTheSavedRadio() {
        val saved = mapOf("driver" to "rtlsdr", "serial" to "00000001", "label" to "V4")
        val devices = listOf(
            RadioDeviceChoice("Other", mapOf("driver" to "rtlsdr", "serial" to "00000002")),
            RadioDeviceChoice("V4", saved),
        )
        assertEquals(devices[1], matchProfileDevice(profile(saved), devices))
    }

    @Test fun serialAllowsAChangedDisplayLabel() {
        val saved = mapOf("driver" to "rtlsdr", "serial" to "00000001", "label" to "Old")
        val radio = RadioDeviceChoice("New", saved + ("label" to "New"))
        assertEquals(radio, matchProfileDevice(profile(saved), listOf(radio)))
    }

    @Test fun missingOrAmbiguousIdentityNeverSelectsAnotherRadio() {
        val saved = mapOf("driver" to "rtlsdr", "serial" to "00000001")
        val other = RadioDeviceChoice("Other", mapOf("driver" to "rtlsdr", "serial" to "00000002"))
        assertNull(matchProfileDevice(profile(saved), listOf(other)))
        val duplicated = listOf(
            RadioDeviceChoice("One", saved + ("label" to "One")),
            RadioDeviceChoice("Two", saved + ("label" to "Two")),
        )
        assertNull(matchProfileDevice(profile(saved), duplicated))
    }

    @Test fun flexDaemonEndpointIdentifiesTheSavedDevice() {
        val saved = mapOf("driver" to "flex1500", "host" to "127.0.0.1", "port" to "15000", "label" to "Old")
        val radio = RadioDeviceChoice("New", saved + ("label" to "New"))
        assertEquals(radio, matchProfileDevice(profile(saved), listOf(radio)))
    }

    @Test fun savedRxControlsRestoreOnlyWhenStillAdvertised() {
        val saved = profile(emptyMap()).copy(
            rxGains = mapOf("LNA" to 12.0, "VGA" to 90.0),
            rxHardwareAgc = false,
            rxAntenna = "RX2",
        )
        val capabilities = RadioChannelCapabilities(
            formats = emptyList(), nativeFormat = null, nativeFullScale = null,
            streamArgs = emptyList(), antennas = listOf("RX1", "RX2"),
            gains = listOf("LNA", "VGA"),
            gainRanges = mapOf("LNA" to RadioRange(0.0, 30.0, 1.0),
                "VGA" to RadioRange(0.0, 50.0, 1.0)),
            currentGainMode = true, automaticGain = true, fullDuplex = null,
            frequencyRanges = emptyList(), sampleRates = emptyList(),
            sampleRateRanges = emptyList(), bandwidths = emptyList(),
            bandwidthRanges = emptyList(), notReported = emptySet(),
        )
        assertEquals(
            SavedRxControls(mapOf("LNA" to 12.0), false, "RX2"),
            supportedSavedRxControls(saved, capabilities),
        )
        assertEquals(SavedRxControls(emptyMap(), null, null),
            supportedSavedRxControls(saved, null))
    }
}
