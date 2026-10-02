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
            val queriedChannels = mutableListOf<Pair<Int, Int>>()
            val future = executor.submit {
                server.accept().use { socket ->
                    fun reply(writer: SoapyRpcWriter) {
                        socket.getOutputStream().write(writer.frame().encode())
                        socket.getOutputStream().flush()
                    }
                    repeat(57) {
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
                            305 -> {
                                val direction = reader.char()
                                val channel = reader.int32()
                                queriedChannels += direction to channel
                                reply(SoapyRpcWriter().string(if (channel == 1) "CF32" else "CS16")
                                    .float64(if (channel == 1) 1.0 else 32768.0))
                            }
                            306 -> {
                                val direction = reader.char()
                                val channel = reader.int32()
                                queriedChannels += direction to channel
                                reply(SoapyRpcWriter().argInfoList(
                                    if (direction == 1 && channel == 0) listOf(
                                        SoapyArgInfo(
                                            "WIRE", "CS16", "Wire format", "Sample transport format", "",
                                            3, SoapyRange(0.0, 0.0, 0.0), listOf("CS16", "CF32"),
                                            listOf("16-bit", "32-bit"),
                                        ),
                                    ) else emptyList(),
                                ))
                            }
                            304, 1202 -> {
                                queriedChannels += reader.char() to reader.int32()
                                reply(SoapyRpcWriter().emptyStringList())
                            }
                            500, 700 -> {
                                val pair = reader.char() to reader.int32()
                                queriedChannels += pair
                                reply(if (pair == (1 to 0)) SoapyRpcWriter().stringList(
                                    if (call == 500) listOf("RX", "RX2") else listOf("LNA"),
                                ) else SoapyRpcWriter().emptyStringList())
                            }
                            708 -> {
                                queriedChannels += reader.char() to reader.int32()
                                assertEquals("LNA", reader.string())
                                reply(SoapyRpcWriter().range(SoapyRange(0.0, 30.0, 1.0)))
                            }
                            706 -> {
                                queriedChannels += reader.char() to reader.int32()
                                assertEquals("LNA", reader.string())
                                reply(SoapyRpcWriter().float64(12.0))
                            }
                            502 -> {
                                queriedChannels += reader.char() to reader.int32()
                                reply(SoapyRpcWriter().string("RX"))
                            }
                            702 -> {
                                queriedChannels += reader.char() to reader.int32()
                                reply(SoapyRpcWriter().bool(false))
                            }
                            805, 906, 907 -> {
                                queriedChannels += reader.char() to reader.int32()
                                reply(SoapyRpcWriter().emptyRangeList())
                            }
                            902, 905 -> {
                                queriedChannels += reader.char() to reader.int32()
                                reply(SoapyRpcWriter().emptyFloat64List())
                            }
                            203, 709 -> {
                                val pair = reader.char() to reader.int32()
                                queriedChannels += pair
                                reply(SoapyRpcWriter().bool(call == 709 && pair == (1 to 0)))
                            }
                            1402 -> reply(SoapyRpcWriter().argInfoList(emptyList()))
                            1200 -> reply(SoapyRpcWriter().emptyStringList())
                            1405 -> {
                                queriedChannels += reader.char() to reader.int32()
                                reply(SoapyRpcWriter().argInfoList(emptyList()))
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
            assertEquals(2, info.allRxCapabilities.size)
            assertEquals(1, info.allTxCapabilities.size)
            assertEquals("CS16", info.allRxCapabilities[0].nativeFormat)
            assertEquals(32768.0, info.allRxCapabilities[0].nativeFullScale)
            assertEquals("CF32", info.allRxCapabilities[1].nativeFormat)
            assertEquals(1.0, info.allRxCapabilities[1].nativeFullScale)
            assertEquals("WIRE", info.allRxCapabilities[0].streamArgs.single().key)
            assertEquals(listOf("CS16", "CF32"), info.allRxCapabilities[0].streamArgs.single().options)
            assertEquals(listOf("16-bit", "32-bit"), info.allRxCapabilities[0].streamArgs.single().optionNames)
            assertEquals(12.0, info.allRxCapabilities[0].currentGains["LNA"])
            assertEquals(SoapyRange(0.0, 30.0, 1.0), info.allRxCapabilities[0].gainRanges["LNA"])
            assertEquals("RX", info.allRxCapabilities[0].currentAntenna)
            assertEquals(false, info.allRxCapabilities[0].currentGainMode)
            assertEquals(
                listOf(1 to 0, 1 to 1, 0 to 0),
                queriedChannels.distinct(),
            )
            assertEquals(
                listOf(20, 1, 100, 101, 102, 1402, 1200, 202, 202) +
                    listOf(700, 708, 706, 500, 709, 305, 1405, 1202, 304, 306, 502, 702, 203, 805, 902, 907, 905, 906) +
                    listOf(700, 500, 709, 305, 1405, 1202, 304, 306, 203, 805, 902, 907, 905, 906) +
                    listOf(700, 500, 709, 305, 1405, 1202, 304, 306, 203, 805, 902, 907, 905, 906) +
                    listOf(2, 3),
                calls,
            )
        }
    }
}
