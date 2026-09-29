package com.kb1jdx.chrissysdr.soapyremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors

class SoapyRemoteClientTest {
    @Test
    fun distinguishesUnsupportedCapabilitiesFromDeviceErrors() {
        assertTrue(SoapyRemoteException("getFullDuplex not supported").isUnsupportedCapability())
        assertTrue(SoapyRemoteException("unsupported operation").isUnsupportedCapability())
        assertFalse(SoapyRemoteException("device disconnected").isUnsupportedCapability())
        assertFalse(SoapyRemoteException("I/O failure").isUnsupportedCapability())
    }

    @Test
    fun opensQueriesAndClosesDevice() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val calls = mutableListOf<Int>()
            val future = executor.submit {
                server.accept().use { socket ->
                    fun reply(writer: SoapyRpcWriter) {
                        socket.getOutputStream().write(writer.frame().encode())
                        socket.getOutputStream().flush()
                    }
                    repeat(29) {
                        val reader = SoapyRpcReader(SoapyRpcFrame.readFrom(socket.getInputStream()).payload)
                        val call = reader.call()
                        calls += call
                        when (call) {
                            20 -> reply(SoapyRpcWriter().string("test-server"))
                            1 -> { reader.kwargs(); reply(SoapyRpcWriter().voidValue()) }
                            100 -> reply(SoapyRpcWriter().string("mock-driver"))
                            101 -> reply(SoapyRpcWriter().string("mock-radio"))
                            102 -> reply(SoapyRpcWriter().kwargs(mapOf("serial" to "123")))
                            202 -> {
                                val direction = reader.char()
                                reply(SoapyRpcWriter().int32(if (direction == 1) 2 else 1))
                            }
                            304, 500, 700 -> {
                                reader.char(); reader.int32()
                                reply(SoapyRpcWriter().emptyStringList())
                            }
                            805, 906, 907 -> {
                                reader.char(); reader.int32()
                                reply(SoapyRpcWriter().emptyRangeList())
                            }
                            902, 905 -> {
                                reader.char(); reader.int32()
                                reply(SoapyRpcWriter().emptyFloat64List())
                            }
                            203, 709 -> {
                                reader.char(); reader.int32()
                                reply(SoapyRpcWriter().bool(false))
                            }
                            2, 3 -> reply(SoapyRpcWriter().voidValue())
                            else -> error("Unexpected call $call")
                        }
                    }
                }
            }

            val info = SoapyRemoteClient().inspect(
                "127.0.0.1",
                server.localPort,
                mapOf("driver" to "mock-driver"),
            )
            future.get()
            executor.shutdown()

            assertEquals("mock-driver", info.driverKey)
            assertEquals("mock-radio", info.hardwareKey)
            assertEquals(mapOf("serial" to "123"), info.hardwareInfo)
            assertEquals(2, info.rxChannels)
            assertEquals(1, info.txChannels)
            assertEquals(
                listOf(20, 1, 100, 101, 102, 202, 202) +
                    listOf(700, 304, 500, 709, 203, 805, 902, 907, 905, 906) +
                    listOf(700, 304, 500, 709, 203, 805, 902, 907, 905, 906) +
                    listOf(2, 3),
                calls,
            )
        }
    }
}
