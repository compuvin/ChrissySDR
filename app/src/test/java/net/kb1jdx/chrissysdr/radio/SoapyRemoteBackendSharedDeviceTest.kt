package com.kb1jdx.chrissysdr.radio

import com.kb1jdx.chrissysdr.soapyremote.SoapyRpcFrame
import com.kb1jdx.chrissysdr.soapyremote.SoapyRpcReader
import com.kb1jdx.chrissysdr.soapyremote.SoapyRpcWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class SoapyRemoteBackendSharedDeviceTest {
    @Test
    fun rxAndTxReuseOneMadeDevice() {
        ServerSocket(0).use { listener ->
            val calls = mutableListOf<Int>()
            val failure = AtomicReference<Throwable?>(null)
            val server = Thread {
                val dataSockets = mutableListOf<Socket>()
                try {
                    listener.accept().use { control ->
                        control.soTimeout = 5_000
                        while (true) {
                            val call = SoapyRpcReader(
                                SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                            ).call()
                            calls += call
                            if (call == 300) {
                                ServerSocket(0).use { streamListener ->
                                    streamListener.soTimeout = 5_000
                                    val port = streamListener.localPort.toString()
                                    reply(control, SoapyRpcWriter().string(port))
                                    dataSockets += streamListener.accept()
                                    dataSockets += streamListener.accept()
                                    reply(control, SoapyRpcWriter().int32(calls.count { it == 300 } - 1)
                                        .string(port))
                                }
                            } else {
                                val response = when (call) {
                                    901 -> SoapyRpcWriter().float64(48_000.0)
                                    904 -> SoapyRpcWriter().float64(20_000.0)
                                    303 -> SoapyRpcWriter().int32(0)
                                    else -> SoapyRpcWriter().voidValue()
                                }
                                reply(control, response)
                            }
                            if (call == 3) break
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    dataSockets.forEach { runCatching { it.close() } }
                }
            }.apply { isDaemon = true; start() }

            val endpoint = RadioEndpoint("127.0.0.1", listener.localPort)
            val arguments = mapOf("driver" to "test")
            val backend = SoapyRemoteBackend()
            val rx = backend.openReceiver(
                ReceiverConfig(endpoint, arguments, 14_200_000.0, 3_000.0, null,
                    48_000.0, "CS16", 32_768.0, mode = "USB"),
                RadioOpenCancellation(),
            )
            rx.close()
            val tx = backend.openTransmitter(
                TransmitterConfig(endpoint, arguments, 14_200_000.0, 48_000.0,
                    "CS16", 32_768.0, mode = "USB"),
            )
            tx.close()
            backend.close()

            server.join(5_000)
            assertEquals(false, server.isAlive)
            failure.get()?.let { throw it }
            assertEquals(1, calls.count { it == 1 }) // MAKE
            assertEquals(2, calls.count { it == 300 }) // RX and TX setupStream
            assertEquals(2, calls.count { it == 301 }) // RX and TX closeStream
            assertEquals(1, calls.count { it == 2 }) // UNMAKE only at the end
        }
    }

    private fun reply(control: Socket, value: SoapyRpcWriter) {
        control.getOutputStream().apply { write(value.frame().encode()); flush() }
    }
}
