package com.kb1jdx.chrissysdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TxAntennaPolicyTest {
    private val txAntennas = listOf("ANT1", "ANT2")

    @Test fun syncUsesMatchingAdvertisedRxAntenna() {
        assertEquals("ANT2", TxAntennaPolicy.requested(txAntennas, "ANT2", true, "ANT1"))
    }

    @Test fun syncNeverSendsAnRxOnlyAntenna() {
        assertNull(TxAntennaPolicy.requested(txAntennas, "RX_A", true, "ANT1"))
    }

    @Test fun manualSelectionMustBeAdvertisedByTx() {
        assertEquals("ANT1", TxAntennaPolicy.requested(txAntennas, "ANT2", false, "ANT1"))
        assertNull(TxAntennaPolicy.requested(txAntennas, "ANT2", false, "RX_A"))
        assertNull(TxAntennaPolicy.requested(emptyList(), "ANT2", true, "ANT1"))
    }
}
