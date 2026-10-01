package com.kb1jdx.chrissysdr.soapyremote

import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapyRemoteTelemetryTest {
    @Test fun readsDeviceSettingsAndGlobalAndChannelSensors() {
        val mode = arg("MODE", "Mode", 3, options = listOf("auto", "manual"))
        val bias = arg("BIAS", "Bias tee", 0)
        val swr = arg("SWR", "Standing wave ratio", 2)
        val forwardPower = arg("forward_power", "Forward power", 2, units = "W")
        withServer { call, request, reply ->
            when (call) {
                20 -> reply(SoapyRpcWriter().string("test-server"))
                1 -> { request.kwargs(); reply(SoapyRpcWriter().voidValue()) }
                100 -> reply(SoapyRpcWriter().string("test-driver"))
                101 -> reply(SoapyRpcWriter().string("test-hardware"))
                102 -> reply(SoapyRpcWriter().kwargs(emptyMap()))
                1402 -> reply(SoapyRpcWriter().argInfoList(listOf(mode)))
                1401 -> {
                    assertEquals("MODE", request.string())
                    reply(SoapyRpcWriter().string("manual"))
                }
                1200 -> reply(SoapyRpcWriter().stringList(listOf("SWR")))
                1204 -> {
                    assertEquals("SWR", request.string())
                    reply(SoapyRpcWriter().argInfo(swr))
                }
                1201 -> {
                    assertEquals("SWR", request.string())
                    reply(SoapyRpcWriter().string("1.2"))
                }
                202 -> reply(SoapyRpcWriter().int32(1))
                700, 304, 305, 306, 1405, 1202, 500, 709, 203, 805, 902, 907, 905, 906,
                1404, 1205, 1203 -> {
                    val direction = request.char()
                    assertEquals(0, request.int32())
                    when (call) {
                        700, 500 -> reply(SoapyRpcWriter().emptyStringList())
                        304 -> reply(SoapyRpcWriter().stringList(listOf("CS16")))
                        305 -> reply(SoapyRpcWriter().string("CS16").float64(32768.0))
                        306 -> reply(SoapyRpcWriter().argInfoList(emptyList()))
                        1405 -> reply(SoapyRpcWriter().argInfoList(if (direction == 1) listOf(bias) else emptyList()))
                        1202 -> reply(SoapyRpcWriter().stringList(if (direction == 0) listOf("forward_power") else emptyList()))
                        709, 203 -> reply(SoapyRpcWriter().bool(false))
                        805, 907, 906 -> reply(SoapyRpcWriter().emptyRangeList())
                        902, 905 -> reply(SoapyRpcWriter().emptyFloat64List())
                        1404 -> {
                            assertEquals("BIAS", request.string())
                            reply(SoapyRpcWriter().string("true"))
                        }
                        1205 -> {
                            assertEquals("forward_power", request.string())
                            reply(SoapyRpcWriter().argInfo(forwardPower))
                        }
                        1203 -> {
                            assertEquals("forward_power", request.string())
                            reply(SoapyRpcWriter().string("5.6"))
                        }
                    }
                }
                2, 3 -> reply(SoapyRpcWriter().voidValue())
                else -> error("Unexpected Soapy call $call")
            }
        }.also { info ->
            assertEquals("manual", info.settings.single().currentValue)
            assertEquals(listOf("auto", "manual"), info.settings.single().info.options)
            assertEquals("1.2", info.sensors.single().currentValue)
            assertEquals("Standing wave ratio", info.sensors.single().info?.name)
            assertEquals("true", info.allRxCapabilities.single().settings.single().currentValue)
            assertEquals("5.6", info.allTxCapabilities.single().sensors.single().currentValue)
            assertEquals("W", info.allTxCapabilities.single().sensors.single().info?.units)
        }
    }

    @Test fun unsupportedOptionalTelemetryIsNotAConnectionError() {
        withServer { call, request, reply ->
            when (call) {
                20 -> reply(SoapyRpcWriter().string("test-server"))
                1 -> { request.kwargs(); reply(SoapyRpcWriter().voidValue()) }
                100, 101 -> reply(SoapyRpcWriter().string("test"))
                102 -> reply(SoapyRpcWriter().kwargs(emptyMap()))
                1402, 1200 -> reply(SoapyRpcWriter().exception("not supported"))
                202 -> reply(SoapyRpcWriter().int32(0))
                2, 3 -> reply(SoapyRpcWriter().voidValue())
                else -> error("Unexpected Soapy call $call")
            }
        }.also { info ->
            assertTrue(info.settings.isEmpty())
            assertTrue(info.sensors.isEmpty())
            assertTrue("device settings" in info.notReported)
            assertTrue("device sensors" in info.notReported)
        }
    }

    private fun arg(
        key: String,
        name: String,
        type: Int,
        units: String = "",
        options: List<String> = emptyList(),
    ) = SoapyArgInfo(
        key, "", name, "", units, type, SoapyRange(0.0, 100.0, 0.1),
        options, options,
    )

    private fun withServer(
        respond: (Int, SoapyRpcReader, (SoapyRpcWriter) -> Unit) -> Unit,
    ): SoapyRemoteDeviceInfo {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit {
                    server.accept().use { socket ->
                        while (true) {
                            val request = SoapyRpcReader(SoapyRpcFrame.readFrom(socket.getInputStream()).payload)
                            val call = request.call()
                            respond(call, request) { writer ->
                                socket.getOutputStream().write(writer.frame().encode())
                                socket.getOutputStream().flush()
                            }
                            if (call == 3) break
                        }
                    }
                }
                val info = SoapyRemoteClient().inspect(
                    "127.0.0.1", server.localPort, mapOf("driver" to "test"),
                )
                future.get(5, TimeUnit.SECONDS)
                return info
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
