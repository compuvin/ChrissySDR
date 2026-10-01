package com.kb1jdx.chrissysdr.radio

import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioOpenCancellationTest {
    @Test fun cancelClosesRegisteredAndFutureSockets() {
        val cancellation = RadioOpenCancellation()
        val pending = Socket()
        cancellation.register(pending)
        cancellation.cancel()
        assertTrue(cancellation.isCancelled())
        assertTrue(pending.isClosed)

        val late = Socket()
        cancellation.register(late)
        assertTrue(late.isClosed)
    }

    @Test fun releaseLeavesOpenedSessionSocketsAlone() {
        val cancellation = RadioOpenCancellation()
        val opened = Socket()
        cancellation.register(opened)
        cancellation.release()
        cancellation.cancel()
        assertFalse(opened.isClosed)
        opened.close()
    }
}
