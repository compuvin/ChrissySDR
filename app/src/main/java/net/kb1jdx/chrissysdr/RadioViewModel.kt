package com.kb1jdx.chrissysdr

import androidx.lifecycle.ViewModel
import com.kb1jdx.chrissysdr.soapyremote.SoapyChannelCapabilities
import com.kb1jdx.chrissysdr.soapyremote.SoapyRange
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteClient
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteDeviceInfo
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteRxSession
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteTxSession
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RadioDeviceChoice(
    val label: String,
    val arguments: Map<String, String>,
)

data class RadioUiState(
    val host: String = "192.168.1.100",
    val port: String = SoapyRemoteClient.DEFAULT_PORT.toString(),
    val frequency: String = "10000000",
    val bandwidth: String = "12000",
    val sampleRateHz: Double? = null,
    val automaticSampleRateHz: Double? = null,
    val sampleRateOptions: List<Double> = emptyList(),
    val sampleRateAutomatic: Boolean = true,
    val appliedSampleRateHz: Double? = null,
    val mode: String = "AM",
    val connectionStatus: String = "Not connected",
    val deviceDetails: String = "",
    val devices: List<RadioDeviceChoice> = emptyList(),
    val discovering: Boolean = false,
    val inspecting: Boolean = false,
    val rxAvailable: Boolean = false,
    val rxActive: Boolean = false,
    val rxBusy: Boolean = false,
    val rxStatus: String = "RX stopped",
    val txAvailable: Boolean = false,
    val txActive: Boolean = false,
    val txBusy: Boolean = false,
    val txStatus: String = "AM TX unavailable",
)

class RadioViewModel : ViewModel() {
    private val client = SoapyRemoteClient()
    private val worker = Executors.newCachedThreadPool()
    private val mutableState = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = mutableState.asStateFlow()

    private var selectedPort = SoapyRemoteClient.DEFAULT_PORT
    private var selectedDevice: Map<String, String>? = null
    private var selectedRxFormat: String? = null
    private var selectedRxCapabilities: SoapyChannelCapabilities? = null
    private var selectedTxFormat: String? = null
    private var selectedTxSampleRate: Double? = null
    private var selectedTxCapabilities: SoapyChannelCapabilities? = null
    @Volatile private var rxSession: SoapyRemoteRxSession? = null
    @Volatile private var txSession: SoapyRemoteTxSession? = null

    fun setHost(value: String) = mutableState.update { it.copy(host = value) }
    fun setPort(value: String) = mutableState.update { it.copy(port = value) }
    fun setFrequency(value: String) = mutableState.update { it.copy(frequency = value) }
    fun setBandwidth(value: String) {
        mutableState.update { it.copy(bandwidth = value) }
        if (mutableState.value.sampleRateAutomatic) updateAutomaticSampleRate()
    }

    fun selectSampleRate(value: Double?) {
        if (value != null && value !in mutableState.value.sampleRateOptions) return
        mutableState.update {
            it.copy(
                sampleRateAutomatic = value == null,
                sampleRateHz = value ?: it.sampleRateHz,
                rxAvailable = selectedRxFormat != null && (value ?: it.automaticSampleRateHz) != null,
                appliedSampleRateHz = null,
            )
        }
        if (value == null) updateAutomaticSampleRate()
    }

    fun discover() {
        val snapshot = mutableState.value
        val port = snapshot.port.toIntOrNull()
        if (snapshot.host.isBlank() || port == null || port !in 1..65535) {
            mutableState.update { it.copy(connectionStatus = "Enter a valid server and port") }
            return
        }
        mutableState.update {
            it.copy(
                discovering = true,
                connectionStatus = "Connecting…",
                devices = emptyList(),
                deviceDetails = "",
            )
        }
        worker.execute {
            runCatching { client.discover(snapshot.host, port) }
                .onSuccess { discovery ->
                    mutableState.update {
                        it.copy(
                            discovering = false,
                            connectionStatus =
                                "Connected to ${discovery.serverId}; ${discovery.devices.size} device(s)",
                            devices = discovery.devices.mapIndexed { index, arguments ->
                                RadioDeviceChoice(
                                    label = arguments["label"] ?: arguments["driver"]
                                        ?: "Device ${index + 1}",
                                    arguments = arguments,
                                )
                            },
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            discovering = false,
                            connectionStatus =
                                "Connection failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                }
        }
    }

    fun inspect(choice: RadioDeviceChoice) {
        val snapshot = mutableState.value
        val port = snapshot.port.toIntOrNull() ?: return
        mutableState.update {
            it.copy(inspecting = true, connectionStatus = "Opening ${choice.label}…")
        }
        worker.execute {
            runCatching { client.inspect(snapshot.host, port, choice.arguments) }
                .onSuccess { info -> configureDevice(snapshot.host, port, choice, info) }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            inspecting = false,
                            connectionStatus =
                                "Device query failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                }
        }
    }

    private fun configureDevice(
        host: String,
        port: Int,
        choice: RadioDeviceChoice,
        info: SoapyRemoteDeviceInfo,
    ) {
        selectedPort = port
        selectedDevice = choice.arguments
        val rxCapabilities = info.rxCapabilities
        selectedRxCapabilities = rxCapabilities
        selectedRxFormat = rxCapabilities.supportedFormat()
        val bandwidth = mutableState.value.bandwidth.toDoubleOrNull() ?: DEFAULT_AM_BANDWIDTH
        val rxRates = rxCapabilities?.let {
            SampleRatePolicy.choose(it.sampleRates, it.sampleRateRanges, bandwidth)
        } ?: SampleRateChoice(null, emptyList())
        val rxRate = rxRates.automaticRate

        val stationOwner = info.hardwareInfo["station_owner"]?.toBooleanStrictOrNull() ?: true
        val transmitEnabled = info.hardwareInfo["daemon_transmit_enabled"]
            ?.toBooleanStrictOrNull() ?: true
        val txCapabilities = info.txCapabilities
        selectedTxFormat = txCapabilities.supportedFormat()
        selectedTxSampleRate = txCapabilities?.let {
            SampleRatePolicy.choose(it.sampleRates, it.sampleRateRanges, bandwidth).automaticRate
        }
        selectedTxCapabilities = if (
            stationOwner && transmitEnabled && selectedTxFormat != null &&
            selectedTxSampleRate != null && txCapabilities?.frequencyRanges?.isNotEmpty() == true
        ) txCapabilities else null

        val txMessage = when {
            !stationOwner -> "AM TX unavailable: this client is not the station owner"
            !transmitEnabled -> "AM TX unavailable: transmit is disabled by the radio server"
            txCapabilities == null -> "AM TX unavailable: no TX channel"
            selectedTxFormat == null -> "AM TX unavailable: no CS16 or CF32 stream"
            selectedTxSampleRate == null -> "AM TX unavailable: no usable TX sample rate"
            txCapabilities.frequencyRanges.isEmpty() -> "AM TX unavailable: no TX frequency ranges"
            else -> "AM TX ready (${selectedTxFormat}, ${formatHz(selectedTxSampleRate!!)})"
        }
        mutableState.update {
            it.copy(
                host = host,
                inspecting = false,
                connectionStatus = "Selected ${choice.label}",
                deviceDetails = formatDeviceInfo(info),
                sampleRateHz = rxRate,
                automaticSampleRateHz = rxRate,
                sampleRateOptions = rxRates.overrideOptions,
                sampleRateAutomatic = true,
                appliedSampleRateHz = null,
                rxAvailable = selectedRxFormat != null && rxRate != null,
                rxStatus = if (selectedRxFormat != null && rxRate != null) {
                    "AM RX ready (${selectedRxFormat}, ${formatHz(rxRate)})"
                } else {
                    "AM RX unavailable for the reported capabilities"
                },
                txAvailable = selectedTxCapabilities != null,
                txStatus = txMessage,
            )
        }
    }

    private fun updateAutomaticSampleRate() {
        val capabilities = selectedRxCapabilities ?: return
        val bandwidth = mutableState.value.bandwidth.toDoubleOrNull() ?: return
        val choice = SampleRatePolicy.choose(
            capabilities.sampleRates,
            capabilities.sampleRateRanges,
            bandwidth,
        )
        mutableState.update {
            it.copy(
                sampleRateHz = choice.automaticRate,
                automaticSampleRateHz = choice.automaticRate,
                sampleRateOptions = choice.overrideOptions,
                rxAvailable = selectedRxFormat != null && choice.automaticRate != null,
                rxStatus = if (choice.automaticRate == null) {
                    "No reported sample rate can support this RX bandwidth"
                } else if (!it.rxActive) {
                    "AM RX ready (${selectedRxFormat}, ${formatHz(choice.automaticRate)})"
                } else it.rxStatus,
            )
        }
    }

    fun startReceiver() {
        val snapshot = mutableState.value
        val device = selectedDevice
        val format = selectedRxFormat
        val frequency = snapshot.frequency.toDoubleOrNull()
        val bandwidth = snapshot.bandwidth.toDoubleOrNull()
        val sampleRate = snapshot.sampleRateHz
        if (device == null || format == null || frequency == null || bandwidth == null ||
            sampleRate == null
        ) {
            mutableState.update { it.copy(rxStatus = "Select a device and enter valid RX values") }
            return
        }
        mutableState.update { it.copy(rxBusy = true, rxStatus = "Opening RX stream…") }
        worker.execute {
            runCatching {
                val session = SoapyRemoteRxSession.open(
                    snapshot.host, selectedPort, device, frequency, bandwidth, sampleRate, format,
                )
                try {
                    session.start(
                        onStatistics = { stats ->
                            mutableState.update {
                                it.copy(
                                    rxStatus = buildString {
                                        append("Playing AM • %.1f ksps".format(stats.samplesPerSecond / 1_000))
                                        append(" • RMS %.1f dBFS".format(stats.rmsDbfs))
                                        append(" • gaps ${stats.sequenceGaps}")
                                    },
                                )
                            }
                        },
                        onError = { error ->
                            mutableState.update {
                                it.copy(
                                    rxActive = false,
                                    rxBusy = false,
                                    rxStatus = "RX failed: ${error.message ?: error.javaClass.simpleName}",
                                )
                            }
                            closeReceiverAsync()
                        },
                    )
                    rxSession = session
                    session
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }.onSuccess { session ->
                mutableState.update {
                    it.copy(
                        rxActive = true,
                        rxBusy = false,
                        appliedSampleRateHz = session.inputSampleRate,
                        rxStatus = "AM audio active • applied ${formatHz(session.inputSampleRate)}",
                    )
                }
            }.onFailure { error ->
                rxSession = null
                mutableState.update {
                    it.copy(
                        rxActive = false,
                        rxBusy = false,
                        rxStatus = "Could not start RX: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun stopReceiver() {
        mutableState.update { it.copy(rxBusy = true, rxStatus = "Stopping RX…") }
        closeReceiverAsync()
    }

    private fun closeReceiverAsync() {
        val session = rxSession
        rxSession = null
        worker.execute {
            runCatching { session?.close() }
            mutableState.update {
                it.copy(rxActive = false, rxBusy = false, rxStatus = "RX stopped")
            }
        }
    }

    fun validateTransmit(): String? {
        val frequency = mutableState.value.frequency.toDoubleOrNull()
            ?: return "Enter a valid transmit frequency"
        val capabilities = selectedTxCapabilities
            ?: return "The selected radio is not available for transmit"
        if (capabilities.frequencyRanges.none { frequency in it.minimum..it.maximum }) {
            return "TX frequency is outside the ranges reported by the radio"
        }
        return null
    }

    fun startTransmitter() {
        val validation = validateTransmit()
        if (validation != null) {
            mutableState.update { it.copy(txStatus = validation) }
            return
        }
        val snapshot = mutableState.value
        val device = selectedDevice ?: return
        val frequency = snapshot.frequency.toDouble()
        val format = selectedTxFormat ?: return
        val sampleRate = selectedTxSampleRate ?: return
        mutableState.update { it.copy(txBusy = true, txStatus = "Stopping RX and opening AM TX…") }
        worker.execute {
            runCatching {
                val receive = rxSession
                rxSession = null
                receive?.close()
                val session = SoapyRemoteTxSession.open(
                    snapshot.host, selectedPort, device, frequency, sampleRate, format,
                )
                try {
                    session.start(
                        onStatistics = { stats ->
                            mutableState.update {
                                it.copy(
                                    txStatus = "TRANSMITTING AM • ${stats.secondsRemaining}s • " +
                                        "mic %.0f%%".format(stats.microphonePeak * 100),
                                )
                            }
                        },
                        onStopped = { stopTransmitter("AM TX time limit reached") },
                        onError = { error ->
                            stopTransmitter(
                                "AM TX failed: ${error.message ?: error.javaClass.simpleName}",
                            )
                        },
                    )
                    txSession = session
                    session
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }.onSuccess { session ->
                mutableState.update {
                    it.copy(
                        rxActive = false,
                        rxBusy = false,
                        rxStatus = "RX stopped for transmit",
                        txActive = true,
                        txBusy = false,
                        txStatus = "TRANSMITTING AM; microphone active • " +
                            "applied ${formatHz(session.outputSampleRate)}",
                    )
                }
            }.onFailure { error ->
                txSession = null
                mutableState.update {
                    it.copy(
                        txActive = false,
                        txBusy = false,
                        txStatus = "Could not start AM TX: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun stopTransmitter(message: String = "AM TX stopped") {
        mutableState.update { it.copy(txBusy = true) }
        val session = txSession
        txSession = null
        worker.execute {
            runCatching { session?.close() }
            mutableState.update {
                it.copy(txActive = false, txBusy = false, txStatus = message)
            }
        }
    }

    fun setPermissionMessage(message: String) {
        mutableState.update { it.copy(txStatus = message) }
    }

    override fun onCleared() {
        val receive = rxSession
        val transmit = txSession
        rxSession = null
        txSession = null
        runCatching { receive?.close() }
        runCatching { transmit?.close() }
        worker.shutdownNow()
    }
}

private fun SoapyChannelCapabilities?.supportedFormat(): String? = when {
    this == null -> null
    "CS16" in formats -> "CS16"
    "CF32" in formats -> "CF32"
    else -> null
}

private fun formatDeviceInfo(info: SoapyRemoteDeviceInfo) = buildString {
    append("Driver: ${info.driverKey}")
    append("\nHardware: ${info.hardwareKey}")
    append("\nRX channels: ${info.rxChannels} • TX channels: ${info.txChannels}")
    info.rxCapabilities?.let { append(formatCapabilities("RX 0", it)) }
    info.txCapabilities?.let { append(formatCapabilities("TX 0", it)) }
    if (info.hardwareInfo.isNotEmpty()) {
        append("\n\nHardware information")
        info.hardwareInfo.forEach { (key, value) -> append("\n$key: $value") }
    }
}

private fun formatCapabilities(label: String, capabilities: SoapyChannelCapabilities) = buildString {
    append("\n\n$label capabilities")
    append("\nFormats: ${capabilities.formats.display()}")
    append("\nAntennas: ${capabilities.antennas.display()}")
    append("\nGain controls: ${capabilities.gains.display()}")
    append("\nFrequency: ${capabilities.frequencyRanges.displayRanges()}")
    append("\nSample rates: ${capabilities.sampleRates.displayHz()}")
    if (capabilities.sampleRateRanges.isNotEmpty()) {
        append("\nSample-rate ranges: ${capabilities.sampleRateRanges.displayRanges()}")
    }
    append("\nBandwidths: ${capabilities.bandwidths.displayHz()}")
    if (capabilities.bandwidthRanges.isNotEmpty()) {
        append("\nBandwidth ranges: ${capabilities.bandwidthRanges.displayRanges()}")
    }
}

private fun List<String>.display() = if (isEmpty()) "not reported" else joinToString()
private fun List<Double>.displayHz() = if (isEmpty()) "not reported" else joinToString { formatHz(it) }
private fun List<SoapyRange>.displayRanges() = if (isEmpty()) "not reported" else joinToString {
    if (it.step > 0.0) "${formatHz(it.minimum)}–${formatHz(it.maximum)} (step ${formatHz(it.step)})"
    else "${formatHz(it.minimum)}–${formatHz(it.maximum)}"
}

internal fun formatHz(value: Double): String = when {
    kotlin.math.abs(value) >= 1_000_000 -> "%.6g MHz".format(value / 1_000_000)
    kotlin.math.abs(value) >= 1_000 -> "%.6g kHz".format(value / 1_000)
    else -> "%.6g Hz".format(value)
}

private const val DEFAULT_AM_BANDWIDTH = 12_000.0
