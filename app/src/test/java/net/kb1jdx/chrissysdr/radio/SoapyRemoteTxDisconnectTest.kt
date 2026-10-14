package com.kb1jdx.chrissysdr.radio

import com.kb1jdx.chrissysdr.soapyremote.SoapyRpcFrame
import com.kb1jdx.chrissysdr.soapyremote.SoapyRpcReader
import com.kb1jdx.chrissysdr.soapyremote.SoapyRpcWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapyRemoteTxDisconnectTest {
    @Test fun preparedTxCloseReportsUnconfirmedWhenServerDisappears() {
        ServerSocket(0).use { listener ->
            val dropConnection = CountDownLatch(1)
            val serverFailure = AtomicReference<Throwable?>(null)
            val server = Thread {
                val dataSockets = mutableListOf<Socket>()
                try {
                    listener.accept().use { control ->
                        control.soTimeout = 5_000
                        while (true) {
                            val call = SoapyRpcReader(
                                SoapyRpcFrame.readFrom(control.getInputStream()).payload,
                            ).call()
                            if (call == 300) {
                                ServerSocket(0).use { streamListener ->
                                    streamListener.soTimeout = 5_000
                                    val port = streamListener.localPort.toString()
                                    reply(control, SoapyRpcWriter().string(port))
                                    dataSockets += streamListener.accept()
                                    dataSockets += streamListener.accept()
                                    reply(control, SoapyRpcWriter().int32(0).string(port))
                                }
                                check(dropConnection.await(5, TimeUnit.SECONDS))
                                break
                            }
                            reply(control, when (call) {
                                901 -> SoapyRpcWriter().float64(48_000.0)
                                else -> SoapyRpcWriter().voidValue()
                            })
                        }
                    }
                } catch (error: Throwable) {
                    serverFailure.set(error)
                } finally {
                    dataSockets.forEach { runCatching { it.close() } }
                }
            }.apply { isDaemon = true; start() }

            val backend = SoapyRemoteBackend()
            try {
                val tx = backend.openTransmitter(TransmitterConfig(
                    RadioEndpoint("127.0.0.1", listener.localPort),
                    mapOf("driver" to "test"), 14_200_000.0, 48_000.0,
                    "CS16", 32_768.0, mode = "USB",
                ))
                dropConnection.countDown()
                server.join(5_000)
                assertFalse("Fake server did not disconnect", server.isAlive)
                serverFailure.get()?.let { throw it }

                val failure = assertThrows(IllegalStateException::class.java) { tx.close() }
                assertTrue(failure.message.orEmpty().contains("TX shutdown unconfirmed"))
            } finally {
                dropConnection.countDown()
                runCatching { backend.close() }
                server.join(5_000)
            }
        }
    }

    private fun reply(control: Socket, value: SoapyRpcWriter) {
        control.getOutputStream().apply { write(value.frame().encode()); flush() }
    }
}
