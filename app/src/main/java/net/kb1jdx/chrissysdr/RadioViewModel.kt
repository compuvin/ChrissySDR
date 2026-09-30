package com.kb1jdx.chrissysdr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kb1jdx.chrissysdr.radio.RadioChannelCapabilities
import com.kb1jdx.chrissysdr.radio.RadioDeviceCapabilities
import com.kb1jdx.chrissysdr.radio.RadioEndpoint
import com.kb1jdx.chrissysdr.radio.RadioRange
import com.kb1jdx.chrissysdr.radio.RadioService
import com.kb1jdx.chrissysdr.radio.ReceiverConfig
import com.kb1jdx.chrissysdr.radio.TransmitterConfig
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
    val port: String = RadioEndpoint.DEFAULT_PORT.toString(),
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
    val txRangesUnreported: Boolean = false,
    val allowUnknownTxRange: Boolean = false,
)

class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("radio_safety", 0)
    private val worker = Executors.newCachedThreadPool()
    private val mutableState = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = mutableState.asStateFlow()

    private var selectedPort = RadioEndpoint.DEFAULT_PORT
    private var selectedDevice: Map<String, String>? = null
    private var selectedRxFormat: StreamFormatChoice? = null
    private var selectedRxCapabilities: RadioChannelCapabilities? = null
    private var selectedTxFormat: StreamFormatChoice? = null
    private var selectedTxSampleRate: Double? = null
    private var selectedTxCapabilities: RadioChannelCapabilities? = null
    private var selectedRadioPreferenceKey: String? = null
    @Volatile private var radioService: RadioService? = null
    @Volatile private var resumeRxAfterTx = false

    fun attachService(service: RadioService) {
        radioService = service
        service.setStateListener { receiving, transmitting ->
            mutableState.update {
                it.copy(
                    rxActive = receiving,
                    rxBusy = false,
                    txActive = transmitting,
                    txBusy = false,
                    rxStatus = if (!receiving && it.rxActive) "RX stopped" else it.rxStatus,
                    txStatus = if (!transmitting && it.txActive) "AM TX stopped" else it.txStatus,
                )
            }
        }
    }

    fun setHost(value: String) = mutableState.update { it.copy(host = value) }
    fun setPort(value: String) = mutableState.update { it.copy(port = value) }
    fun setFrequency(value: String) = mutableState.update { it.copy(frequency = value) }
    fun setBandwidth(value: String) {
        mutableState.update { it.copy(bandwidth = value) }
        if (mutableState.value.sampleRateAutomatic) updateAutomaticSampleRate()
        else {
            val passband = value.toDoubleOrNull()
            mutableState.update {
                val available = selectedRxFormat != null && it.sampleRateHz != null &&
                    passband != null && passband.isFinite() && passband > 0.0 &&
                    it.sampleRateHz >= passband * 1.25 && hardwareBandwidthAvailable(passband)
                it.copy(
                    rxAvailable = available,
                    rxStatus = if (!available && !it.rxActive) "No supported RX bandwidth or sample rate contains this passband" else it.rxStatus,
                )
            }
        }
    }

    fun selectSampleRate(value: Double?) {
        if (value != null && value !in mutableState.value.sampleRateOptions) return
        mutableState.update {
            it.copy(
                sampleRateAutomatic = value == null,
                sampleRateHz = value ?: it.sampleRateHz,
                rxAvailable = selectedRxFormat != null && (value ?: it.automaticSampleRateHz) != null &&
                    it.bandwidth.toDoubleOrNull()?.let { passband ->
                        passband.isFinite() && passband > 0.0 &&
                            (value ?: it.automaticSampleRateHz)!! >= passband * 1.25 &&
                            hardwareBandwidthAvailable(passband)
                    } == true,
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
            val service = radioService
            if (service == null) {
                mutableState.update {
                    it.copy(discovering = false, connectionStatus = "Radio service is not ready")
                }
                return@execute
            }
            runCatching { service.discover(RadioEndpoint(snapshot.host, port)) }
                .onSuccess { discovery ->
                    mutableState.update {
                        it.copy(
                            discovering = false,
                            connectionStatus =
                                "Connected to ${discovery.serverId}; ${discovery.devices.size} device(s)",
                            devices = discovery.devices.map { device ->
                                RadioDeviceChoice(
                                    label = device.label,
                                    arguments = device.arguments,
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
            val service = radioService
            if (service == null) {
                mutableState.update {
                    it.copy(inspecting = false, connectionStatus = "Radio service is not ready")
                }
                return@execute
            }
            runCatching {
                service.inspect(RadioEndpoint(snapshot.host, port), choice.arguments)
            }
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
        info: RadioDeviceCapabilities,
    ) {
        selectedPort = port
        selectedDevice = choice.arguments
        val radioPreferenceKey = unknownRangePreferenceKey(host, port, choice.arguments)
        selectedRadioPreferenceKey = radioPreferenceKey
        val rxCapabilities = info.rx
        selectedRxCapabilities = rxCapabilities
        selectedRxFormat = rxCapabilities.supportedFormat()
        val bandwidth = mutableState.value.bandwidth.toDoubleOrNull() ?: DEFAULT_AM_BANDWIDTH
        val hardwareBandwidth = rxCapabilities?.let {
            BandwidthPolicy.choose(it.bandwidths, it.bandwidthRanges, bandwidth)
        }
        val bandwidthAvailable = rxCapabilities == null ||
            !BandwidthPolicy.isReported(rxCapabilities.bandwidths, rxCapabilities.bandwidthRanges) ||
            hardwareBandwidth != null
        val rxRates = rxCapabilities?.let {
            SampleRatePolicy.choose(it.sampleRates, it.sampleRateRanges, bandwidth)
        } ?: SampleRateChoice(null, emptyList())
        val rxRate = rxRates.automaticRate

        val stationOwner = info.metadata["station_owner"]?.toBooleanStrictOrNull() ?: true
        val transmitEnabled = info.metadata["daemon_transmit_enabled"]
            ?.toBooleanStrictOrNull() ?: true
        val txCapabilities = info.tx
        selectedTxFormat = txCapabilities.supportedFormat()
        selectedTxSampleRate = txCapabilities?.let {
            SampleRatePolicy.choose(it.sampleRates, it.sampleRateRanges, bandwidth).automaticRate
        }
        val txRangesUnreported = txCapabilities?.frequencyRanges?.isEmpty() == true
        val allowUnknownTxRange = txRangesUnreported && preferences.getBoolean(
            radioPreferenceKey,
            false,
        )
        selectedTxCapabilities = if (
            stationOwner && transmitEnabled && selectedTxFormat != null &&
            selectedTxSampleRate != null && txCapabilities != null
        ) txCapabilities else null
        val txAvailable = selectedTxCapabilities != null &&
            (!txRangesUnreported || allowUnknownTxRange)

        val txMessage = when {
            !stationOwner -> "AM TX unavailable: this client is not the station owner"
            !transmitEnabled -> "AM TX unavailable: transmit is disabled by the radio server"
            txCapabilities == null -> "AM TX unavailable: no TX channel"
            selectedTxFormat == null -> "AM TX unavailable: no supported stream format"
            selectedTxSampleRate == null -> "AM TX unavailable: no usable TX sample rate"
            txRangesUnreported && !allowUnknownTxRange ->
                "AM TX disabled: the radio did not report TX frequency limits"
            else -> "AM TX ready (${selectedTxFormat!!.format}, ${formatHz(selectedTxSampleRate!!)})"
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
                rxAvailable = selectedRxFormat != null && rxRate != null && bandwidthAvailable,
                rxStatus = if (selectedRxFormat != null && rxRate != null && bandwidthAvailable) {
                    "AM RX ready (${selectedRxFormat!!.format}, ${formatHz(rxRate)}; hardware BW ${hardwareBandwidth?.let(::formatHz) ?: "not reported"})"
                } else {
                    "AM RX unavailable: no supported rate or hardware bandwidth contains the passband"
                },
                txAvailable = txAvailable,
                txStatus = txMessage,
                txRangesUnreported = txRangesUnreported,
                allowUnknownTxRange = allowUnknownTxRange,
            )
        }
    }

    fun setAllowUnknownTxRange(enabled: Boolean) {
        val key = selectedRadioPreferenceKey ?: return
        val capabilities = selectedTxCapabilities ?: return
        if (capabilities.frequencyRanges.isNotEmpty()) return
        preferences.edit().putBoolean(key, enabled).apply()
        mutableState.update {
            it.copy(
                allowUnknownTxRange = enabled,
                txAvailable = enabled,
                txStatus = if (enabled) {
                    "AM TX ready with unknown hardware frequency limits; operator validation required"
                } else {
                    "AM TX disabled: the radio did not report TX frequency limits"
                },
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
                rxAvailable = selectedRxFormat != null && choice.automaticRate != null &&
                    hardwareBandwidthAvailable(bandwidth),
                rxStatus = if (choice.automaticRate == null || !hardwareBandwidthAvailable(bandwidth)) {
                    "No supported RX bandwidth or sample rate contains this passband"
                } else if (!it.rxActive) {
                    "AM RX ready (${selectedRxFormat!!.format}, ${formatHz(choice.automaticRate)})"
                } else it.rxStatus,
            )
        }
    }

    private fun hardwareBandwidthAvailable(passbandHz: Double): Boolean {
        if (!passbandHz.isFinite() || passbandHz <= 0.0) return false
        val capabilities = selectedRxCapabilities ?: return false
        if (!BandwidthPolicy.isReported(capabilities.bandwidths, capabilities.bandwidthRanges)) return true
        return BandwidthPolicy.choose(capabilities.bandwidths, capabilities.bandwidthRanges, passbandHz) != null
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
        if (!bandwidth.isFinite() || bandwidth <= 0.0 || !hardwareBandwidthAvailable(bandwidth)) {
            mutableState.update { it.copy(rxStatus = "No supported hardware bandwidth contains this passband") }
            return
        }
        val capabilities = selectedRxCapabilities ?: return
        val hardwareBandwidth = BandwidthPolicy.choose(
            capabilities.bandwidths, capabilities.bandwidthRanges, bandwidth,
        )
        mutableState.update { it.copy(rxBusy = true, rxStatus = "Opening RX stream…") }
        worker.execute {
            val service = radioService
            if (service == null) {
                mutableState.update {
                    it.copy(rxBusy = false, rxStatus = "Radio service is not ready")
                }
                return@execute
            }
            runCatching {
                service.startReceiver(
                    ReceiverConfig(
                        endpoint = RadioEndpoint(snapshot.host, selectedPort),
                        deviceArguments = device,
                        frequencyHz = frequency,
                        bandwidthHz = bandwidth,
                        hardwareBandwidthHz = hardwareBandwidth,
                        sampleRate = sampleRate,
                        format = format.format,
                        fullScale = format.fullScale,
                    ),
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
                    },
                )
            }.onSuccess { appliedSampleRate ->
                mutableState.update {
                    it.copy(
                        rxActive = true,
                        rxBusy = false,
                        appliedSampleRateHz = appliedSampleRate,
                        rxStatus = "AM audio active • applied ${formatHz(appliedSampleRate)}",
                    )
                }
            }.onFailure { error ->
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
        worker.execute {
            runCatching { radioService?.stopReceiver() }
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
        if (capabilities.frequencyRanges.isEmpty() && !mutableState.value.allowUnknownTxRange) {
            return "TX is disabled because the radio did not report frequency limits"
        }
        if (capabilities.frequencyRanges.isNotEmpty() &&
            capabilities.frequencyRanges.none { frequency in it.minimum..it.maximum }
        ) {
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
        resumeRxAfterTx = snapshot.rxActive
        mutableState.update { it.copy(txBusy = true, txStatus = "Stopping RX and opening AM TX…") }
        worker.execute {
            val service = radioService
            if (service == null) {
                mutableState.update {
                    it.copy(txBusy = false, txStatus = "Radio service is not ready")
                }
                return@execute
            }
            runCatching {
                service.startTransmitter(
                    TransmitterConfig(
                        endpoint = RadioEndpoint(snapshot.host, selectedPort),
                        deviceArguments = device,
                        frequencyHz = frequency,
                        sampleRate = sampleRate,
                        format = format.format,
                        fullScale = format.fullScale,
                    ),
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
            }.onSuccess { appliedSampleRate ->
                mutableState.update {
                    it.copy(
                        rxActive = false,
                        rxBusy = false,
                        rxStatus = "RX stopped for transmit",
                        txActive = true,
                        txBusy = false,
                        txStatus = "TRANSMITTING AM; microphone active • " +
                            "applied ${formatHz(appliedSampleRate)}",
                    )
                }
            }.onFailure { error ->
                val resumeReceiver = resumeRxAfterTx
                resumeRxAfterTx = false
                mutableState.update {
                    it.copy(
                        txActive = false,
                        txBusy = false,
                        txStatus = "Could not start AM TX: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
                if (resumeReceiver) startReceiver()
            }
        }
    }

    fun stopTransmitter(message: String = "AM TX stopped") {
        mutableState.update { it.copy(txBusy = true) }
        val resumeReceiver = resumeRxAfterTx
        resumeRxAfterTx = false
        worker.execute {
            runCatching { radioService?.stopTransmitter() }
            mutableState.update {
                it.copy(txActive = false, txBusy = false, txStatus = message)
            }
            if (resumeReceiver) startReceiver()
        }
    }

    fun setPermissionMessage(message: String) {
        mutableState.update { it.copy(txStatus = message) }
    }

    override fun onCleared() {
        resumeRxAfterTx = false
        radioService?.setStateListener(null)
        radioService = null
        worker.shutdownNow()
    }
}

private fun RadioChannelCapabilities?.supportedFormat(): StreamFormatChoice? = this?.let {
    StreamFormatPolicy.choose(it.formats, it.nativeFormat, it.nativeFullScale)
}

private fun formatDeviceInfo(info: RadioDeviceCapabilities) = buildString {
    append("Driver: ${info.driverKey}")
    append("\nHardware: ${info.hardwareKey}")
    append("\nRX channels: ${info.rxChannels} • TX channels: ${info.txChannels}")
    info.allRx.forEachIndexed { channel, capabilities ->
        append(formatCapabilities("RX $channel", capabilities))
    }
    info.allTx.forEachIndexed { channel, capabilities ->
        append(formatCapabilities("TX $channel", capabilities))
    }
    if (info.metadata.isNotEmpty()) {
        append("\n\nHardware information")
        info.metadata.forEach { (key, value) -> append("\n$key: $value") }
    }
}

private fun formatCapabilities(label: String, capabilities: RadioChannelCapabilities) = buildString {
    append("\n\n$label capabilities")
    append("\nFormats: ${capabilities.formats.display()}")
    append("\nNative format: ${capabilities.nativeFormat ?: "not reported"}")
    capabilities.nativeFullScale?.let { append(" (full scale $it)") }
    append("\nStream arguments: ${if (capabilities.streamArgs.isEmpty()) "none reported" else ""}")
    capabilities.streamArgs.forEach { arg ->
        val type = listOf("boolean", "integer", "float", "string").getOrNull(arg.type)
            ?: "type ${arg.type}"
        append("\n  ${arg.key}: $type")
        if (arg.options.isNotEmpty()) append("; options ${arg.options.joinToString()}")
        else if (arg.range.maximum > arg.range.minimum) {
            append("; range ${arg.range.minimum}–${arg.range.maximum}")
        }
        if (arg.value.isNotEmpty()) append("; default ${arg.value}")
    }
    append("\nAntennas: ${capabilities.antennas.display()}")
    append("\nGain controls: ${capabilities.gains.joinToString().ifEmpty { "not reported" }}")
    capabilities.gainRanges.forEach { (name, range) ->
        append("\n  $name: ${listOf(range).displayRanges()}")
    }
    append("\nAutomatic gain: ${capabilities.automaticGain.reportedBoolean()}")
    append("\nFull duplex: ${capabilities.fullDuplex.reportedBoolean()}")
    append("\nFrequency: ${capabilities.frequencyRanges.displayRanges()}")
    append("\nSample rates: ${capabilities.sampleRates.displayHz()}")
    if (capabilities.sampleRateRanges.isNotEmpty()) {
        append("\nSample-rate ranges: ${capabilities.sampleRateRanges.displayRanges()}")
    }
    append("\nBandwidths: ${capabilities.bandwidths.displayHz()}")
    if (capabilities.bandwidthRanges.isNotEmpty()) {
        append("\nBandwidth ranges: ${capabilities.bandwidthRanges.displayRanges()}")
    }
    if (capabilities.notReported.isNotEmpty()) {
        append("\nNot reported: ${capabilities.notReported.joinToString()}")
    }
}

private fun Boolean?.reportedBoolean(): String = when (this) {
    true -> "yes"
    false -> "no"
    null -> "not reported"
}

private fun unknownRangePreferenceKey(
    host: String,
    port: Int,
    deviceArgs: Map<String, String>,
): String = buildString {
    append("allow_unknown_tx_range|")
    append(host.trim())
    append(':')
    append(port)
    append('|')
    deviceArgs.toSortedMap().forEach { (key, value) -> append(key).append('=').append(value).append(';') }
}

private fun List<String>.display() = if (isEmpty()) "not reported" else joinToString()
private fun List<Double>.displayHz() = if (isEmpty()) "not reported" else joinToString { formatHz(it) }
private fun List<RadioRange>.displayRanges() = if (isEmpty()) "not reported" else joinToString {
    if (it.step > 0.0) "${formatHz(it.minimum)}–${formatHz(it.maximum)} (step ${formatHz(it.step)})"
    else "${formatHz(it.minimum)}–${formatHz(it.maximum)}"
}

internal fun formatHz(value: Double): String = when {
    kotlin.math.abs(value) >= 1_000_000 -> "%.6g MHz".format(value / 1_000_000)
    kotlin.math.abs(value) >= 1_000 -> "%.6g kHz".format(value / 1_000)
    else -> "%.6g Hz".format(value)
}

private const val DEFAULT_AM_BANDWIDTH = 12_000.0
