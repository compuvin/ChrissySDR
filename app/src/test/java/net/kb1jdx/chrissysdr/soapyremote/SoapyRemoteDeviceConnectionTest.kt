package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.radio.RadioEndpoint
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class SoapyRemoteDeviceConnectionTest {
    @Test
    fun oneMadeDeviceServesSuccessiveRequestsUntilClose() {
        ServerSocket(0).use { listener ->
            val calls = mutableListOf<Int>()
            val serverFailure = AtomicReference<Throwable?>(null)
            val server = Thread {
                try {
                    listener.accept().use { socket ->
                        repeat(4) {
                            val reader = SoapyRpcReader(
                                SoapyRpcFrame.readFrom(socket.getInputStream()).payload,
                            )
                            val call = reader.call()
                            if (call == 1) reader.kwargs()
                            reader.requireFinished()
                            calls += call
                            socket.getOutputStream().apply {
                                write(SoapyRpcWriter().voidValue().frame().encode())
                                flush()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    serverFailure.set(error)
                }
            }.apply { isDaemon = true; start() }
            SoapyRemoteDeviceConnection.open(
                RadioEndpoint("127.0.0.1", listener.localPort), mapOf("driver" to "test"),
            ).use { device ->
                device.transact(SoapyRpcWriter().call(900)) { it.requireVoid() }
                assertEquals(true, device.isUsable)
            }
            server.join(3_000)
            assertEquals(false, server.isAlive)
            serverFailure.get()?.let { throw it }
            assertEquals(listOf(1, 900, 2, 3), calls)
        }
    }
}
