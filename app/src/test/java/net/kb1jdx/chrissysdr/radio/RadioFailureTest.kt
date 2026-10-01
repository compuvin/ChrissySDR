package com.kb1jdx.chrissysdr.radio

import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioFailureTest {
    @Test fun classifiesNetworkDriverAndStreamFailures() {
        assertEquals(
            RadioFailureKind.NETWORK,
            RadioFailure.from("RX", SocketException("connection reset")).kind,
        )
        assertEquals(
            RadioFailureKind.DRIVER,
            RadioFailure.from("Open", SoapyRemoteException("device busy")).kind,
        )
        assertEquals(
            RadioFailureKind.STREAM,
            RadioFailure.from("RX", SoapyStreamException("overflow")).kind,
        )
    }

    @Test fun retriesOnlyReceiveTransportErrorsAndStopsAfterThreeAttempts() {
        assertEquals(1L, RxReconnectPolicy.delaySeconds(RadioFailureKind.NETWORK, 0, true))
        assertEquals(2L, RxReconnectPolicy.delaySeconds(RadioFailureKind.STREAM, 1, true))
        assertEquals(4L, RxReconnectPolicy.delaySeconds(RadioFailureKind.NETWORK, 2, true))
        assertNull(RxReconnectPolicy.delaySeconds(RadioFailureKind.NETWORK, 3, true))
        assertNull(RxReconnectPolicy.delaySeconds(RadioFailureKind.DRIVER, 0, true))
        assertNull(RxReconnectPolicy.delaySeconds(RadioFailureKind.CONFIGURATION, 0, true))
    }

    @Test fun initialRxOpenFailureDoesNotRetry() {
        assertNull(RxReconnectPolicy.delaySeconds(RadioFailureKind.NETWORK, 0, false))
        assertNull(RxReconnectPolicy.delaySeconds(RadioFailureKind.STREAM, 0, false))
    }
}
