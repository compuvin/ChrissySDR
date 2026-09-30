package com.kb1jdx.chrissysdr.radio

data class RadioEndpoint(val host: String, val port: Int = DEFAULT_PORT) {
    companion object { const val DEFAULT_PORT = 55132 }
}

data class RadioDeviceDescriptor(
    val label: String,
    val arguments: Map<String, String>,
)

data class RadioDiscovery(
    val serverId: String,
    val devices: List<RadioDeviceDescriptor>,
)

data class RadioRange(val minimum: Double, val maximum: Double, val step: Double)

data class RadioArgumentInfo(
    val key: String,
    val value: String,
    val name: String,
    val description: String,
    val units: String,
    val type: Int,
    val range: RadioRange,
    val options: List<String>,
    val optionNames: List<String>,
)

data class RadioChannelCapabilities(
    val formats: List<String>,
    val nativeFormat: String?,
    val nativeFullScale: Double?,
    val streamArgs: List<RadioArgumentInfo>,
    val antennas: List<String>,
    val gains: List<String>,
    val gainRanges: Map<String, RadioRange>,
    val automaticGain: Boolean?,
    val fullDuplex: Boolean?,
    val frequencyRanges: List<RadioRange>,
    val sampleRates: List<Double>,
    val sampleRateRanges: List<RadioRange>,
    val bandwidths: List<Double>,
    val bandwidthRanges: List<RadioRange>,
    val notReported: Set<String>,
)

data class RadioDeviceCapabilities(
    val driverKey: String,
    val hardwareKey: String,
    val metadata: Map<String, String>,
    val rxChannels: Int,
    val txChannels: Int,
    val rx: RadioChannelCapabilities?,
    val tx: RadioChannelCapabilities?,
    val allRx: List<RadioChannelCapabilities> = listOfNotNull(rx),
    val allTx: List<RadioChannelCapabilities> = listOfNotNull(tx),
)

data class ReceiverConfig(
    val endpoint: RadioEndpoint,
    val deviceArguments: Map<String, String>,
    val frequencyHz: Double,
    val bandwidthHz: Double,
    val hardwareBandwidthHz: Double?,
    val sampleRate: Double,
    val format: String,
)

data class TransmitterConfig(
    val endpoint: RadioEndpoint,
    val deviceArguments: Map<String, String>,
    val frequencyHz: Double,
    val sampleRate: Double,
    val format: String,
)

data class ReceiverStatistics(
    val totalSamples: Long,
    val samplesPerSecond: Double,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val sequenceGaps: Long,
)

data class TransmitterStatistics(
    val totalSamples: Long,
    val samplesPerSecond: Double,
    val microphonePeak: Double,
    val secondsRemaining: Int,
)

interface RadioReceiver : AutoCloseable {
    val appliedSampleRate: Double
    fun start(onStatistics: (ReceiverStatistics) -> Unit, onError: (Throwable) -> Unit)
}

interface RadioTransmitter : AutoCloseable {
    val appliedSampleRate: Double
    fun start(
        onStatistics: (TransmitterStatistics) -> Unit,
        onStopped: () -> Unit,
        onError: (Throwable) -> Unit,
    )
}

interface RadioBackend {
    fun discover(endpoint: RadioEndpoint): RadioDiscovery
    fun inspect(endpoint: RadioEndpoint, deviceArguments: Map<String, String>): RadioDeviceCapabilities
    fun openReceiver(config: ReceiverConfig): RadioReceiver
    fun openTransmitter(config: TransmitterConfig): RadioTransmitter
}
