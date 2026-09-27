package net.kb1jdx.chrissysdr.soapyremote

import java.net.InetSocketAddress
import java.net.Socket

data class SoapyRemoteDiscovery(val serverId: String, val devices: List<Map<String, String>>)

data class SoapyRemoteDeviceInfo(
    val driverKey: String,
    val hardwareKey: String,
    val hardwareInfo: Map<String, String>,
    val rxChannels: Int,
    val txChannels: Int,
    val rxCapabilities: SoapyChannelCapabilities?,
    val txCapabilities: SoapyChannelCapabilities?,
)

data class SoapyChannelCapabilities(
    val formats: List<String>,
    val antennas: List<String>,
    val gains: List<String>,
    val frequencyRanges: List<SoapyRange>,
    val sampleRates: List<Double>,
    val sampleRateRanges: List<SoapyRange>,
    val bandwidths: List<Double>,
    val bandwidthRanges: List<SoapyRange>,
)

class SoapyRemoteClient(
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
) {
    fun discover(host: String, port: Int = DEFAULT_PORT): SoapyRemoteDiscovery {
        require(host.isNotBlank()) { "Server address is required" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.trim(), port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val serverId = transact(socket, SoapyRpcWriter().call(GET_SERVER_ID)) { it.string() }
            val devices = transact(socket, SoapyRpcWriter().call(FIND).kwargs(mapOf(STOP_KEY to ""))) {
                it.kwargsList()
            }
            transact(socket, SoapyRpcWriter().call(HANGUP)) { it.requireVoid() }
            return SoapyRemoteDiscovery(serverId, devices)
        }
    }

    fun inspect(host: String, port: Int, deviceArgs: Map<String, String>): SoapyRemoteDeviceInfo {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host.trim(), port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            var made = false
            try {
                transact(socket, SoapyRpcWriter().call(GET_SERVER_ID)) { it.string() }
                transact(socket, SoapyRpcWriter().call(MAKE).kwargs(deviceArgs - STOP_KEY)) {
                    it.requireVoid()
                }
                made = true
                val driver = transact(socket, SoapyRpcWriter().call(GET_DRIVER_KEY)) { it.string() }
                val hardware = transact(socket, SoapyRpcWriter().call(GET_HARDWARE_KEY)) { it.string() }
                val info = transact(socket, SoapyRpcWriter().call(GET_HARDWARE_INFO)) { it.kwargs() }
                val rx = getNumChannels(socket, RX)
                val tx = getNumChannels(socket, TX)
                val rxCapabilities = if (rx > 0) getCapabilities(socket, RX) else null
                val txCapabilities = if (tx > 0) getCapabilities(socket, TX) else null
                return SoapyRemoteDeviceInfo(
                    driver, hardware, info, rx, tx, rxCapabilities, txCapabilities,
                )
            } finally {
                runCatching {
                    if (made) transact(socket, SoapyRpcWriter().call(UNMAKE)) { it.requireVoid() }
                    transact(socket, SoapyRpcWriter().call(HANGUP)) { it.requireVoid() }
                }
            }
        }
    }

    private fun getNumChannels(socket: Socket, direction: Int): Int = transact(
        socket,
        SoapyRpcWriter().call(GET_NUM_CHANNELS).char(direction),
    ) { it.int32() }

    private fun getCapabilities(socket: Socket, direction: Int): SoapyChannelCapabilities {
        fun request(call: Int) = SoapyRpcWriter().call(call).char(direction).int32(0)
        return SoapyChannelCapabilities(
            formats = transact(socket, request(GET_STREAM_FORMATS)) { it.stringList() },
            antennas = transact(socket, request(LIST_ANTENNAS)) { it.stringList() },
            gains = transact(socket, request(LIST_GAINS)) { it.stringList() },
            frequencyRanges = transact(socket, request(GET_FREQUENCY_RANGE)) { it.rangeList() },
            sampleRates = transact(socket, request(LIST_SAMPLE_RATES)) { it.float64List() },
            sampleRateRanges = transact(socket, request(GET_SAMPLE_RATE_RANGE)) { it.rangeList() },
            bandwidths = transact(socket, request(LIST_BANDWIDTHS)) { it.float64List() },
            bandwidthRanges = transact(socket, request(GET_BANDWIDTH_RANGE)) { it.rangeList() },
        )
    }

    private fun <T> transact(socket: Socket, request: SoapyRpcWriter, decode: (SoapyRpcReader) -> T): T {
        socket.getOutputStream().apply { write(request.frame().encode()); flush() }
        return decode(SoapyRpcReader(SoapyRpcFrame.readFrom(socket.getInputStream()).payload))
    }

    companion object {
        const val DEFAULT_PORT = 55132
        private const val FIND = 0
        private const val MAKE = 1
        private const val UNMAKE = 2
        private const val HANGUP = 3
        private const val GET_SERVER_ID = 20
        private const val GET_DRIVER_KEY = 100
        private const val GET_HARDWARE_KEY = 101
        private const val GET_HARDWARE_INFO = 102
        private const val GET_NUM_CHANNELS = 202
        private const val GET_STREAM_FORMATS = 304
        private const val LIST_ANTENNAS = 500
        private const val LIST_GAINS = 700
        private const val GET_FREQUENCY_RANGE = 805
        private const val LIST_SAMPLE_RATES = 902
        private const val LIST_BANDWIDTHS = 905
        private const val GET_BANDWIDTH_RANGE = 906
        private const val GET_SAMPLE_RATE_RANGE = 907
        private const val TX = 0
        private const val RX = 1
        private const val STOP_KEY = "soapy_remote_no_deeper"
    }
}
