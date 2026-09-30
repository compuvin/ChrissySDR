package com.kb1jdx.chrissysdr.soapyremote

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
    val allRxCapabilities: List<SoapyChannelCapabilities> = listOfNotNull(rxCapabilities),
    val allTxCapabilities: List<SoapyChannelCapabilities> = listOfNotNull(txCapabilities),
)

data class SoapyChannelCapabilities(
    val formats: List<String>,
    val nativeFormat: String?,
    val nativeFullScale: Double?,
    val streamArgs: List<SoapyArgInfo>,
    val antennas: List<String>,
    val gains: List<String>,
    val gainRanges: Map<String, SoapyRange>,
    val automaticGain: Boolean?,
    val fullDuplex: Boolean?,
    val frequencyRanges: List<SoapyRange>,
    val sampleRates: List<Double>,
    val sampleRateRanges: List<SoapyRange>,
    val bandwidths: List<Double>,
    val bandwidthRanges: List<SoapyRange>,
    val notReported: Set<String>,
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
                val allRxCapabilities = (0 until rx).map { getCapabilities(socket, RX, it) }
                val allTxCapabilities = (0 until tx).map { getCapabilities(socket, TX, it) }
                return SoapyRemoteDeviceInfo(
                    driver, hardware, info, rx, tx,
                    allRxCapabilities.firstOrNull(), allTxCapabilities.firstOrNull(),
                    allRxCapabilities, allTxCapabilities,
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

    private fun getCapabilities(socket: Socket, direction: Int, channel: Int): SoapyChannelCapabilities {
        fun request(call: Int) = SoapyRpcWriter().call(call).char(direction).int32(channel)
        val notReported = linkedSetOf<String>()
        fun <T> optional(label: String, fallback: T, query: () -> T): T = try {
            query()
        } catch (error: SoapyRemoteException) {
            if (!error.isUnsupportedCapability()) throw error
            notReported += label
            fallback
        }
        val gains = optional("gain controls", emptyList()) {
            transact(socket, request(LIST_GAINS)) { it.stringList() }
        }
        val gainRanges = gains.mapNotNull { name ->
            optional("gain range: $name", null) {
                transact(socket, request(GET_GAIN_ELEMENT_RANGE).string(name)) { it.range() }
            }?.let { name to it }
        }.toMap()
        val nativeFormat = optional("native stream format and full scale", null) {
            transact(socket, request(GET_NATIVE_STREAM_FORMAT)) { reader ->
                reader.string() to reader.float64()
            }
        }
        return SoapyChannelCapabilities(
            formats = transact(socket, request(GET_STREAM_FORMATS)) { it.stringList() },
            nativeFormat = nativeFormat?.first,
            nativeFullScale = nativeFormat?.second,
            streamArgs = optional("stream arguments", emptyList()) {
                transact(socket, request(GET_STREAM_ARGS_INFO)) { it.argInfoList() }
            },
            antennas = optional("antennas", emptyList()) {
                transact(socket, request(LIST_ANTENNAS)) { it.stringList() }
            },
            gains = gains,
            gainRanges = gainRanges,
            automaticGain = optional("automatic gain", null) {
                transact(socket, request(HAS_GAIN_MODE)) { it.bool() }
            },
            fullDuplex = optional("duplex capability", null) {
                transact(socket, request(GET_FULL_DUPLEX)) { it.bool() }
            },
            frequencyRanges = optional("frequency ranges", emptyList()) {
                transact(socket, request(GET_FREQUENCY_RANGE)) { it.rangeList() }
            },
            sampleRates = optional("discrete sample rates", emptyList()) {
                transact(socket, request(LIST_SAMPLE_RATES)) { it.float64List() }
            },
            sampleRateRanges = optional("sample-rate ranges", emptyList()) {
                transact(socket, request(GET_SAMPLE_RATE_RANGE)) { it.rangeList() }
            },
            bandwidths = optional("discrete bandwidths", emptyList()) {
                transact(socket, request(LIST_BANDWIDTHS)) { it.float64List() }
            },
            bandwidthRanges = optional("bandwidth ranges", emptyList()) {
                transact(socket, request(GET_BANDWIDTH_RANGE)) { it.rangeList() }
            },
            notReported = notReported,
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
        private const val GET_FULL_DUPLEX = 203
        private const val GET_STREAM_FORMATS = 304
        private const val GET_NATIVE_STREAM_FORMAT = 305
        private const val GET_STREAM_ARGS_INFO = 306
        private const val LIST_ANTENNAS = 500
        private const val LIST_GAINS = 700
        private const val GET_GAIN_ELEMENT_RANGE = 708
        private const val HAS_GAIN_MODE = 709
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

internal fun SoapyRemoteException.isUnsupportedCapability(): Boolean {
    val text = message.orEmpty().lowercase()
    return "not supported" in text || "unsupported" in text || "not implemented" in text
}
