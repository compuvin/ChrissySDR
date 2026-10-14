package com.kb1jdx.chrissysdr.soapyremote

import com.kb1jdx.chrissysdr.audio.MicrophoneInput
import com.kb1jdx.chrissysdr.radio.RadioEndpoint
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapyRemoteTxFailureTest {
    @Test fun txStreamStatusErrorStopsWriterAndClosesStream() = runFailureCase(
        streamStatusError = true,
        expectedError = "TX stream status -7",
    )

    @Test fun microphoneReadFailureStopsWriterAndClosesStream() = runFailureCase(
        streamStatusError = false,
        expectedError = "synthetic microphone read failure",
    )

    @Test fun applicationCallbackFailureAfterActivationClosesStream() = runFailureCase(
        streamStatusError = false,
        expectedError = "synthetic app callback failure",
        startupCallbackFailure = true,
    )

    private fun runFailureCase(
        streamStatusError: Boolean,
        expectedError: String,
        startupCallbackFailure: Boolean = false,
    ) {
        ServerSocket(0).use { listener ->
            val calls = mutableListOf<Int>()
            val serverFailure = AtomicReference<Throwable?>(null)
            val reportedFailure = AtomicReference<Throwable?>(null)
            val errorReported = CountDownLatch(1)
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
                                    val header = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                                        .putInt(24).putInt(0).putInt(1).putInt(0).putLong(0)
                                    dataSockets.first().getOutputStream().apply {
                                        write(header.array()); flush()
                                    }
                                    if (streamStatusError) {
                                        val status = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                                            .putInt(24).putInt(0).putInt(-7).putInt(0).putLong(0)
                                        dataSockets.last().getOutputStream().apply {
                                            write(status.array()); flush()
                                        }
                                    }
                                }
                                303 -> reply(control, SoapyRpcWriter().int32(0))
                                901 -> reply(control, SoapyRpcWriter().float64(48_000.0))
                                else -> reply(control, SoapyRpcWriter().voidValue())
                            }
                            if (call == 3) break
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
                microphoneFactory = { TestMicrophone(failRead = !streamStatusError) },
            )
            try {
                if (startupCallbackFailure) {
                    val failure = runCatching {
                        tx.start(30, onStatistics = {}, onStopped = {}, onError = {},
                            onStartupEvent = { event, _ ->
                                if (event == "TX activation complete") {
                                    error("synthetic app callback failure")
                                }
                            })
                    }.exceptionOrNull()
                    reportedFailure.set(failure)
                } else {
                    tx.start(30, onStatistics = {}, onStopped = {},
                        onError = { reportedFailure.set(it); errorReported.countDown() },
                        onStartupEvent = { _, _ -> })
                    assertTrue("TX error callback did not fire",
                        errorReported.await(5, TimeUnit.SECONDS))
                }
                assertTrue(reportedFailure.get()?.message.orEmpty().contains(expectedError))
                tx.close() // The failed writer already completed confirmed cleanup.
            } finally {
                runCatching { tx.close() }
                runCatching { device.close() }
                server.join(5_000)
            }
            assertFalse("Fake server did not exit", server.isAlive)
            serverFailure.get()?.let { throw it }
            assertEquals(1, calls.count { it == 303 }) // deactivateStream
            assertEquals(1, calls.count { it == 301 }) // closeStream
        }
    }

    private fun reply(control: Socket, value: SoapyRpcWriter) {
        control.getOutputStream().apply { write(value.frame().encode()); flush() }
    }

    private class TestMicrophone(private val failRead: Boolean) : MicrophoneInput {
        override fun start() {}
        override fun read(samples: ShortArray): Int {
            if (failRead) error("synthetic microphone read failure")
            Thread.sleep(100) // Let the localhost status packet reach the client.
            samples.fill(0)
            return samples.size
        }
        override fun close() {}
    }
}
