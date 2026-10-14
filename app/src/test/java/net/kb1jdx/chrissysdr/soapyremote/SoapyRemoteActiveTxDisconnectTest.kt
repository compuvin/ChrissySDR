package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.audio.MicrophoneInput
import com.kb1jdx.chrissysdr.radio.RadioEndpoint
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapyRemoteActiveTxDisconnectTest {
    @Test fun activeTxReportsFailureWhenServerDropsAfterFirstPacket() {
        ServerSocket(0).use { listener ->
            val firstPacket = CountDownLatch(1)
            val errorReported = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
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
                            when (call) {
                                300 -> ServerSocket(0).use { streamListener ->
                                    streamListener.soTimeout = 5_000
                                    val port = streamListener.localPort.toString()
                                    reply(control, SoapyRpcWriter().string(port))
                                    dataSockets += streamListener.accept()
                                    dataSockets += streamListener.accept()
                                    reply(control, SoapyRpcWriter().int32(0).string(port))
                                }
                                302 -> {
                                    reply(control, SoapyRpcWriter().int32(0))
                                    val ack = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                                        .putInt(24).putInt(0).putInt(1).putInt(0).putLong(0)
                                    dataSockets.first().getOutputStream().apply {
                                        write(ack.array()); flush()
                                    }
                                    val input = DataInputStream(dataSockets.first().getInputStream())
                                    val bytes = input.readInt()
                                    require(bytes in 24..4_048)
                                    input.readFully(ByteArray(bytes - 4))
                                    firstPacket.countDown()
                                    break // Simulate the server and both stream sockets disappearing.
                                }
                                901 -> reply(control, SoapyRpcWriter().float64(48_000.0))
                                else -> reply(control, SoapyRpcWriter().voidValue())
                            }
                        }
                    }
                } catch (error: Throwable) {
                    serverFailure.set(error)
                } finally {
                    dataSockets.forEach { runCatching { it.close() } }
                }
            }.apply { isDaemon = true; start() }

            val device = SoapyRemoteDeviceConnection.open(
                RadioEndpoint("127.0.0.1", listener.localPort), mapOf("driver" to "test"),
            )
            val tx = SoapyRemoteTxSession.open(
                device, 14_200_000.0, 48_000.0, "CS16", 32_768.0, "USB", emptyMap(),
                microphoneFactory = { SyntheticMicrophone() },
            )
            try {
                tx.start(30, onStatistics = {}, onStopped = {},
                    onError = { failure.set(it); errorReported.countDown() },
                    onStartupEvent = { _, _ -> })
                assertTrue("No TX packet reached the fake server",
                    firstPacket.await(5, TimeUnit.SECONDS))
                assertTrue("TX failure was not reported",
                    errorReported.await(5, TimeUnit.SECONDS))
                assertTrue(failure.get()?.message.orEmpty().contains("TX shutdown unconfirmed"))
                assertFalse("Control connection should be invalidated", device.isUsable)
            } finally {
                runCatching { tx.close() }
                runCatching { device.close() }
                server.join(5_000)
            }
            assertFalse("Fake server did not exit", server.isAlive)
            serverFailure.get()?.let { throw it }
        }
    }

    private fun reply(control: Socket, value: SoapyRpcWriter) {
        control.getOutputStream().apply { write(value.frame().encode()); flush() }
    }

    private class SyntheticMicrophone : MicrophoneInput {
        override fun start() {}
        override fun read(samples: ShortArray): Int {
            samples.fill(0)
            return samples.size
        }
        override fun close() {}
    }
}
