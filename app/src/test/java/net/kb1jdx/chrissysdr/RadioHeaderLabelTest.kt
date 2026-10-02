package com.kb1jdx.chrissysdr

import com.kb1jdx.chrissysdr.radio.RadioConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioHeaderLabelTest {
    @Test fun disconnectedHeaderInvitesQuickConnect() {
        assertEquals("No radio connected — tap to connect", headerRadioLabel(RadioUiState()))
    }

    @Test fun activeSavedProfileUsesItsName() {
        val state = RadioUiState(
            rxActive = true,
            connectionState = RadioConnectionState.CONNECTED,
            activeProfileId = "profile-1",
            profileName = "Forty meter radio",
            selectedDeviceLabel = "FLEX-1500",
        )
        assertEquals("Forty meter radio", headerRadioLabel(state))
    }

    @Test fun selectedRadioWithoutRxShowsStoppedState() {
        val state = RadioUiState(
            connectionState = RadioConnectionState.CONNECTED,
            selectedDeviceLabel = "RTL-SDR V4",
        )
        assertEquals("RTL-SDR V4 • RX stopped", headerRadioLabel(state))
    }

    @Test fun reconnectingStatusTakesPrecedence() {
        val state = RadioUiState(
            connectionState = RadioConnectionState.RECONNECTING,
            connectionStatus = "Reconnecting to server…",
            selectedDeviceLabel = "RTL-SDR V4",
        )
        assertEquals("Reconnecting to server…", headerRadioLabel(state))
    }
}
