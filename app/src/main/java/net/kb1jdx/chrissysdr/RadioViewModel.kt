package com.kb1jdx.chrissysdr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kb1jdx.chrissysdr.radio.RadioChannelCapabilities
import com.kb1jdx.chrissysdr.radio.RadioArgumentInfo
import com.kb1jdx.chrissysdr.radio.RadioDeviceCapabilities
import com.kb1jdx.chrissysdr.radio.RadioEndpoint
import com.kb1jdx.chrissysdr.radio.RadioConnectionState
import com.kb1jdx.chrissysdr.radio.RadioFailure
import com.kb1jdx.chrissysdr.radio.RxReconnectPolicy
import com.kb1jdx.chrissysdr.radio.RadioRange
import com.kb1jdx.chrissysdr.radio.RadioService
import com.kb1jdx.chrissysdr.radio.RadioSensor
import com.kb1jdx.chrissysdr.radio.RadioSetting
import com.kb1jdx.chrissysdr.radio.SpectrumFrame
import com.kb1jdx.chrissysdr.radio.SpectrumPolicy
import com.kb1jdx.chrissysdr.radio.ReceiverConfig
import com.kb1jdx.chrissysdr.radio.ReceiverDspSettings
import com.kb1jdx.chrissysdr.radio.TransmitterConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.math.BigDecimal
import kotlin.math.round
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
    val bandwidth: String = ModeBandwidthDefaults.AM_HZ.toString(),
    val sampleRateHz: Double? = null,
    val automaticSampleRateHz: Double? = null,
    val sampleRateOptions: List<Double> = emptyList(),
    val sampleRateAutomatic: Boolean = true,
    val appliedSampleRateHz: Double? = null,
    val rxStreamFormat: String? = null,
    val rxRequestedSampleRateHz: Double? = null,
    val rxHardwareBandwidthHz: Double? = null,
    val rxAppliedHardwareBandwidthHz: Double? = null,
    val rxStreamRateHz: Double? = null,
    val rxSequenceGaps: Long? = null,
    val observedRxOverflows: Int = 0,
    val observedTxUnderflows: Int = 0,
    val recentErrors: List<String> = emptyList(),
    val mode: String = "AM",
    val nfmAudioCutoffHz: Double = 3_000.0,
    val nfmDeemphasisUs: Int = 75,
    val connectionStatus: String = "Not connected",
    val connectionState: RadioConnectionState = RadioConnectionState.DISCONNECTED,
    val lastError: RadioFailure? = null,
    val deviceDetails: String = "",
    val additionalDeviceDetails: String = "",
    val profiles: List<RadioProfile> = emptyList(),
    val profileName: String = "",
    val activeProfileId: String? = null,
    val loadingProfile: Boolean = false,
    val savingProfile: Boolean = false,
    val profileStatus: String = "",
    val selectedDeviceLabel: String = "",
    val devices: List<RadioDeviceChoice> = emptyList(),
    val discovering: Boolean = false,
    val inspecting: Boolean = false,
    val rxAvailable: Boolean = false,
    val rxActive: Boolean = false,
    val rxBusy: Boolean = false,
    val rxStatus: String = "RX stopped",
    val spectrum: SpectrumFrame? = null,
    val spectrumAveraging: Float = 0.35f,
    val spectrumFloorDb: Int = -120,
    val spectrumRangeDb: Int = 120,
    val spectrumSpanHz: Double? = null,
    val tuningStepHz: Double = 100.0,
    val rxGainRanges: Map<String, RadioRange> = emptyMap(),
    val rxGainValues: Map<String, Double> = emptyMap(),
    val rxHardwareAgcSupported: Boolean = false,
    val rxHardwareAgc: Boolean? = null,
    val rxAntennas: List<String> = emptyList(),
    val rxAntenna: String? = null,
    val txAvailable: Boolean = false,
    val txFrequencyRanges: List<RadioRange> = emptyList(),
    val txActive: Boolean = false,
    val txBusy: Boolean = false,
    val txStatus: String = "AM TX unavailable",
    val txAttemptError: String? = null,
    val txRangesUnreported: Boolean = false,
    val allowUnknownTxRange: Boolean = false,
)

class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("radio_safety", 0)
    private val profileDao = ChrissyDatabase.get(application).radioProfiles()
    private val worker = Executors.newCachedThreadPool()
    private val retryScheduler = Executors.newSingleThreadScheduledExecutor()
    private val rxGeneration = AtomicLong()
    private val txErrorGeneration = AtomicLong()
    private var pendingRxRetune: ScheduledFuture<*>? = null
    @Volatile private var activeRxFrequencyHz: Double? = null
    @Volatile private var activeReceiverConfig: ReceiverConfig? = null
    private val mutableState = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = mutableState.asStateFlow()

    private var selectedPort = RadioEndpoint.DEFAULT_PORT
    private var selectedHost: String? = null
    private var selectedDevice: Map<String, String>? = null
    private var selectedRxFormat: StreamFormatChoice? = null
    private var selectedRxCapabilities: RadioChannelCapabilities? = null
    private var rxGainOverrides: Map<String, Double> = emptyMap()
    private var rxHardwareAgcOverride: Boolean? = null
    private var rxAntennaOverride: String? = null
    private var selectedTxFormat: StreamFormatChoice? = null
    private var selectedTxSampleRate: Double? = null
    private var selectedTxCapabilities: RadioChannelCapabilities? = null
    private var selectedRadioPreferenceKey: String? = null
    @Volatile private var radioService: RadioService? = null
    @Volatile private var resumeRxAfterTx = false

    init {
        worker.execute { refreshProfiles() }
    }

    private fun refreshProfiles() {
        runCatching { profileDao.all().map { it.toProfile() } }
            .onSuccess { profiles -> mutableState.update { it.copy(profiles = profiles) } }
            .onFailure { error ->
                mutableState.update { it.copy(profileStatus = "Could not read profiles: ${error.message}") }
            }
    }

    fun setProfileName(value: String) = mutableState.update { it.copy(profileName = value) }

    fun saveRadioProfile() {
        val snapshot = mutableState.value
        if (snapshot.savingProfile || snapshot.loadingProfile) return
        val arguments = selectedDevice
        val name = snapshot.profileName.trim()
        val port = snapshot.port.toIntOrNull()
        if (arguments == null || snapshot.selectedDeviceLabel.isBlank() || name.isBlank() ||
            snapshot.host.isBlank() || port == null || port !in 1..65535 ||
            selectedHost != snapshot.host.trim() || selectedPort != port ||
            snapshot.frequency.toDoubleOrNull()?.let { !it.isFinite() || it <= 0.0 } != false ||
            snapshot.bandwidth.toDoubleOrNull()?.let { !it.isFinite() || it <= 0.0 } != false
        ) {
            mutableState.update { it.copy(profileStatus = "Select a radio and enter a name and valid settings first") }
            return
        }
        val profile = RadioProfile(
            id = snapshot.activeProfileId ?: UUID.randomUUID().toString(),
            name = name,
            host = snapshot.host.trim(),
            port = port,
            deviceLabel = snapshot.selectedDeviceLabel,
            deviceArguments = arguments.toMap(),
            frequency = snapshot.frequency,
            bandwidth = snapshot.bandwidth,
            mode = snapshot.mode,
            sampleRateOverrideHz = if (snapshot.sampleRateAutomatic) null else snapshot.sampleRateHz,
            rxGains = (snapshot.rxGainValues + rxGainOverrides).filterValues { it.isFinite() },
            rxHardwareAgc = (rxHardwareAgcOverride ?: snapshot.rxHardwareAgc)
                .takeIf { snapshot.rxHardwareAgcSupported },
            rxAntenna = rxAntennaOverride ?: snapshot.rxAntenna,
        )
        mutableState.update { it.copy(savingProfile = true, profileStatus = "Saving ${profile.name}…") }
        worker.execute {
            runCatching { profileDao.upsert(profile.toEntity()) }
                .onSuccess {
                    refreshProfiles()
                    mutableState.update {
                        it.copy(activeProfileId = profile.id, savingProfile = false,
                            profileStatus = "Saved ${profile.name}")
                    }
                }
                .onFailure { error ->
                    mutableState.update { it.copy(savingProfile = false,
                        profileStatus = "Could not save profile: ${error.message}") }
                }
        }
    }

    fun loadRadioProfile(id: String) {
        val snapshot = mutableState.value
        if (snapshot.loadingProfile || snapshot.savingProfile || snapshot.discovering || snapshot.inspecting ||
            snapshot.txActive || snapshot.txBusy
        ) return
        mutableState.update {
            it.copy(loadingProfile = true, profileStatus = "Loading radio profile…")
        }
        worker.execute {
            runCatching {
                val profile = requireNotNull(profileDao.byId(id)?.toProfile()) { "Profile no longer exists" }
                val service = checkNotNull(radioService) { "Radio service is not ready" }
                rxGeneration.incrementAndGet()
                service.cancelPendingReceiver()
                service.stopReceiver()
                mutableState.update {
                    it.copy(
                        host = profile.host, port = profile.port.toString(),
                        frequency = profile.frequency, bandwidth = profile.bandwidth,
                        mode = profile.mode,
                        txAttemptError = null,
                        devices = emptyList(), deviceDetails = "", additionalDeviceDetails = "",
                        connectionState = RadioConnectionState.CONNECTING,
                        connectionStatus = "Connecting to ${profile.name}…",
                        rxActive = false, rxBusy = false, rxStatus = "Opening saved radio…",
                        rxAvailable = false, txAvailable = false,
                        txFrequencyRanges = emptyList(),
                        rxGainRanges = emptyMap(), rxGainValues = emptyMap(),
                        rxHardwareAgcSupported = false, rxHardwareAgc = null,
                        rxAntennas = emptyList(), rxAntenna = null,
                        activeProfileId = null, profileName = profile.name,
                    )
                }
                selectedDevice = null
                selectedHost = null
                selectedRxCapabilities = null
                rxGainOverrides = emptyMap()
                rxHardwareAgcOverride = null
                rxAntennaOverride = null
                selectedTxCapabilities = null
                val discovery = service.discover(RadioEndpoint(profile.host, profile.port))
                val devices = discovery.devices.map { RadioDeviceChoice(it.label, it.arguments) }
                mutableState.update { it.copy(devices = devices) }
                val choice = requireNotNull(matchProfileDevice(profile, devices)) {
                    "Saved radio not found uniquely on ${profile.host}:${profile.port}"
                }
                val info = service.inspect(RadioEndpoint(profile.host, profile.port), choice.arguments)
                configureDevice(profile.host, profile.port, choice, info)
                val savedControls = supportedSavedRxControls(profile, info.rx)
                rxGainOverrides = savedControls.gains
                rxHardwareAgcOverride = savedControls.hardwareAgc
                rxAntennaOverride = savedControls.antenna
                mutableState.update {
                    it.copy(
                        rxGainValues = it.rxGainValues + rxGainOverrides,
                        rxHardwareAgc = rxHardwareAgcOverride ?: it.rxHardwareAgc,
                        rxAntenna = rxAntennaOverride ?: it.rxAntenna,
                    )
                }
                profile.sampleRateOverrideHz?.let { savedRate ->
                    if (savedRate in mutableState.value.sampleRateOptions) selectSampleRate(savedRate)
                }
                mutableState.update {
                    it.copy(
                        activeProfileId = profile.id,
                        profileName = profile.name,
                        profileStatus = "${profile.name}: starting RX…",
                    )
                }
                check(mutableState.value.rxAvailable) {
                    "Saved radio connected, but its RX settings are not supported"
                }
                startReceiver()
            }.onFailure { error ->
                val failure = RadioFailure.from("Profile connection failed", error)
                mutableState.update {
                    it.copy(
                        connectionState = RadioConnectionState.FAILED,
                        connectionStatus = failure.displayMessage,
                        profileStatus = failure.displayMessage,
                        lastError = failure,
                        recentErrors = (it.recentErrors + failure.displayMessage).takeLast(5),
                    )
                }
            }
            mutableState.update { it.copy(loadingProfile = false) }
        }
    }

    fun attachService(service: RadioService) {
        radioService = service
        service.setExternalStopListener { reason ->
            rxGeneration.incrementAndGet()
            pendingRxRetune?.cancel(false)
            service.cancelPendingReceiver()
            resumeRxAfterTx = false
            mutableState.update {
                it.copy(
                    rxActive = false,
                    rxBusy = false,
                    spectrum = null,
                    txActive = false,
                    txBusy = false,
                    rxStatus = reason,
                    txStatus = reason,
                )
            }
        }
        service.setStateListener { receiving, transmitting ->
            mutableState.update {
                it.copy(
                    rxActive = receiving,
                    rxBusy = false,
                    spectrum = if (receiving) it.spectrum else null,
                    txActive = transmitting,
                    txBusy = false,
                    rxStatus = if (!receiving && it.rxActive) "RX stopped" else it.rxStatus,
                    txStatus = if (!transmitting && it.txActive) "AM TX stopped" else it.txStatus,
                )
            }
        }
    }

    fun serviceDisconnected() {
        rxGeneration.incrementAndGet()
        radioService = null
        mutableState.update {
            it.copy(
                rxActive = false,
                rxBusy = false,
                spectrum = null,
                txActive = false,
                txBusy = false,
                connectionState = RadioConnectionState.DISCONNECTED,
                connectionStatus = "Radio service disconnected",
                rxStatus = "RX stopped: radio service disconnected",
                txStatus = "AM TX stopped: radio service disconnected",
            )
        }
    }

    fun setHost(value: String) = mutableState.update { it.copy(host = value) }
    fun setPort(value: String) = mutableState.update { it.copy(port = value) }
    fun setSpectrumAveraging(value: Float) = mutableState.update {
        it.copy(spectrumAveraging = value.coerceIn(0.05f, 1f))
    }
    fun setSpectrumFloorDb(value: Int) = mutableState.update {
        it.copy(spectrumFloorDb = value.coerceIn(-160, -20))
    }
    fun setSpectrumRangeDb(value: Int) = mutableState.update {
        it.copy(spectrumRangeDb = value.coerceIn(20, 140))
    }
    fun setSpectrumSpanHz(value: Double?) {
        mutableState.update {
            val maximum = SpectrumPolicy.displayRate(it.appliedSampleRateHz ?: it.sampleRateHz ?: 48_000.0)
            it.copy(spectrumSpanHz = value?.takeIf { span ->
                span.isFinite() && span > 0.0 && span <= maximum
            })
        }
        val snapshot = mutableState.value
        if (snapshot.rxActive) {
            runCatching { radioService?.setReceiverSpectrumSpan(snapshot.spectrumSpanHz) }
        }
    }
    fun setTuningStepHz(value: Double) {
        if (value !in TUNING_STEPS_HZ) return
        mutableState.update { it.copy(tuningStepHz = value) }
    }
    fun setRxGain(name: String, value: Double) {
        val snapshot = mutableState.value
        val range = snapshot.rxGainRanges[name] ?: return
        if (!value.isFinite() || value !in range.minimum..range.maximum ||
            (snapshot.rxHardwareAgcSupported && snapshot.rxHardwareAgc != false) ||
            snapshot.txActive || snapshot.txBusy || snapshot.rxBusy
        ) return
        val applied = if (range.step.isFinite() && range.step > 0.0) {
            (range.minimum + round((value - range.minimum) / range.step) * range.step)
                .coerceIn(range.minimum, range.maximum)
        } else value
        rxGainOverrides = rxGainOverrides + (name to applied)
        if (snapshot.rxActive) startReceiver()
        else mutableState.update { it.copy(rxGainValues = it.rxGainValues + (name to applied)) }
    }
    fun setRxHardwareAgc(enabled: Boolean) {
        val snapshot = mutableState.value
        if (!snapshot.rxHardwareAgcSupported || snapshot.rxHardwareAgc == null ||
            snapshot.txActive || snapshot.txBusy || snapshot.rxBusy
        ) return
        rxHardwareAgcOverride = enabled
        if (snapshot.rxActive) startReceiver()
        else mutableState.update { it.copy(rxHardwareAgc = enabled) }
    }
    fun setRxAntenna(name: String) {
        val snapshot = mutableState.value
        if (snapshot.rxAntennas.size < 2 || name !in snapshot.rxAntennas ||
            snapshot.txActive || snapshot.txBusy || snapshot.rxBusy
        ) return
        rxAntennaOverride = name
        if (snapshot.rxActive) startReceiver()
        else mutableState.update { it.copy(rxAntenna = name) }
    }
    fun stepFrequency(direction: Int) {
        val snapshot = mutableState.value
        if (snapshot.rxBusy || snapshot.txActive || snapshot.txBusy) return
        val target = FrequencyStepPolicy.next(
            snapshot.frequency.toDoubleOrNull() ?: Double.NaN,
            snapshot.tuningStepHz,
            direction,
        )
        if (target == null) {
            mutableState.update { it.copy(rxStatus = "Frequency step would produce an invalid frequency") }
            return
        }
        setFrequency(BigDecimal.valueOf(target).stripTrailingZeros().toPlainString())
    }
    fun tuneSpectrumTo(frequencyHz: Double) {
        val snapshot = mutableState.value
        if (!snapshot.rxActive || snapshot.rxBusy || snapshot.txActive || snapshot.txBusy ||
            !frequencyHz.isFinite() || frequencyHz <= 0.0 || frequencyHz > Long.MAX_VALUE.toDouble()
        ) return
        val target = frequencyHz.toLong().toString()
        if (target != snapshot.frequency) setFrequency(target)
    }
    fun setFrequency(value: String) {
        if (mutableState.value.txActive || mutableState.value.txBusy) return
        mutableState.update {
            val passband = it.bandwidth.toDoubleOrNull()
            val available = validRxFrequency(value) && selectedRxFormat != null &&
                it.sampleRateHz != null && passband != null &&
                SampleRatePolicy.isUsable(it.sampleRateHz, it.mode, passband) &&
                hardwareBandwidthAvailable(passband, it.mode)
            it.copy(
                frequency = value,
                txAttemptError = null,
                rxAvailable = available,
                rxStatus = if (!validRxFrequency(value)) "Enter a valid RX frequency" else if (!it.rxActive && available)
                    "${it.mode} RX ready" else it.rxStatus,
            )
        }
        pendingRxRetune?.cancel(false)
        val snapshot = mutableState.value
        if (!snapshot.rxActive || !snapshot.rxAvailable) return
        scheduleFrequencyTune()
    }
    fun setMode(value: String) {
        val defaultBandwidth = ModeBandwidthDefaults.forMode(value) ?: return
        val snapshot = mutableState.value
        if (value == snapshot.mode || snapshot.rxBusy || snapshot.txActive || snapshot.txBusy
        ) return
        mutableState.update {
            it.copy(
                mode = value,
                txAttemptError = null,
                txAvailable = value == "AM" && selectedTxCapabilities != null &&
                    (!it.txRangesUnreported || it.allowUnknownTxRange),
            )
        }
        setBandwidth(defaultBandwidth.toString())
    }

    fun setNfmAudioCutoff(value: Double) {
        val snapshot = mutableState.value
        if (snapshot.mode != "NFM" || value !in NFM_AUDIO_CUTOFFS_HZ ||
            snapshot.rxBusy || snapshot.txActive || snapshot.txBusy ||
            snapshot.nfmAudioCutoffHz == value
        ) return
        mutableState.update { it.copy(nfmAudioCutoffHz = value) }
        if (snapshot.rxActive) scheduleRxDspUpdate()
    }

    fun setNfmDeemphasis(value: Int) {
        val snapshot = mutableState.value
        if (snapshot.mode != "NFM" || value !in NFM_DEEMPHASIS_US ||
            snapshot.rxBusy || snapshot.txActive || snapshot.txBusy ||
            snapshot.nfmDeemphasisUs == value
        ) return
        mutableState.update { it.copy(nfmDeemphasisUs = value) }
        if (snapshot.rxActive) scheduleRxDspUpdate()
    }

    fun setBandwidth(value: String) {
        if (mutableState.value.txActive || mutableState.value.txBusy) return
        pendingRxRetune?.cancel(false)
        mutableState.update { it.copy(bandwidth = value) }
        val enteredPassband = value.toDoubleOrNull()
        if (enteredPassband == null || !enteredPassband.isFinite() || enteredPassband <= 0.0) {
            mutableState.update { it.copy(rxAvailable = false, rxStatus = "Enter a positive passband width") }
            return
        }
        if (mutableState.value.mode == "NFM" && enteredPassband > 38_000.0) {
            mutableState.update { it.copy(rxAvailable = false, rxStatus = "NFM RF width must be 38 kHz or less") }
            return
        }
        if (mutableState.value.sampleRateAutomatic) updateAutomaticSampleRate()
        else {
            val passband = enteredPassband
            mutableState.update {
                val available = selectedRxFormat != null && it.sampleRateHz != null &&
                    validRxFrequency(it.frequency) &&
                    SampleRatePolicy.isUsable(it.sampleRateHz, it.mode, passband) &&
                    hardwareBandwidthAvailable(passband, it.mode)
                it.copy(
                    rxAvailable = available,
                    rxStatus = if (!available) "Selected sample rate, hardware bandwidth, or frequency is unavailable" else if (!it.rxActive)
                        "${it.mode} RX ready" else it.rxStatus,
                )
            }
        }
        val snapshot = mutableState.value
        if (snapshot.rxActive && !snapshot.rxBusy && snapshot.rxAvailable &&
            !snapshot.txActive && !snapshot.txBusy
        ) scheduleRxDspUpdate()
    }

    private fun scheduleRxDspUpdate() {
        pendingRxRetune?.cancel(false)
        val generation = rxGeneration.get()
        pendingRxRetune = retryScheduler.schedule(
            {
                val current = mutableState.value
                val passband = current.bandwidth.toDoubleOrNull()
                if (generation != rxGeneration.get() || !current.rxActive || current.rxBusy ||
                    !current.rxAvailable || current.txActive || current.txBusy || passband == null
                ) return@schedule
                val appliedRate = current.appliedSampleRateHz
                if (appliedRate == null || !SampleRatePolicy.isUsable(appliedRate, current.mode, passband) ||
                    (!current.sampleRateAutomatic && current.sampleRateHz != appliedRate)
                ) {
                    startReceiver()
                    return@schedule
                }
                val capabilities = selectedRxCapabilities ?: return@schedule
                val service = radioService ?: return@schedule
                val hardwareBandwidth = BandwidthPolicy.forSpectrum(
                    capabilities.bandwidths, capabilities.bandwidthRanges,
                    ModeBandwidthDefaults.centeredRfWidth(current.mode, passband),
                    SpectrumPolicy.displayRate(appliedRate),
                )
                val settings = ReceiverDspSettings(
                    current.mode, passband, current.nfmAudioCutoffHz,
                    current.nfmDeemphasisUs, hardwareBandwidth,
                )
                runCatching { service.reconfigureReceiver(settings) }
                    .onSuccess { appliedBandwidth ->
                        if (generation == rxGeneration.get()) {
                            mutableState.update {
                                it.copy(
                                    sampleRateHz = appliedRate,
                                    rxHardwareBandwidthHz = hardwareBandwidth,
                                    rxAppliedHardwareBandwidthHz = appliedBandwidth,
                                    rxStatus = "${settings.mode} audio active • applied ${formatHz(appliedRate)}",
                                )
                            }
                            activeReceiverConfig = service.receiverConfiguration()
                            if (mutableState.value.frequency.toDoubleOrNull() != activeRxFrequencyHz) {
                                scheduleFrequencyTune()
                            }
                        }
                    }
                    .onFailure { error ->
                        if (generation == rxGeneration.get()) {
                            val previous = service.receiverConfiguration()
                            mutableState.update {
                                it.copy(
                                    mode = previous?.mode ?: it.mode,
                                    bandwidth = previous?.bandwidthHz?.toLong()?.toString() ?: it.bandwidth,
                                    nfmAudioCutoffHz = previous?.nfmAudioCutoffHz ?: it.nfmAudioCutoffHz,
                                    nfmDeemphasisUs = previous?.nfmDeemphasisUs ?: it.nfmDeemphasisUs,
                                    sampleRateHz = previous?.sampleRate ?: it.sampleRateHz,
                                    rxAvailable = previous != null,
                                    txAvailable = previous?.mode == "AM" && selectedTxCapabilities != null &&
                                        (!it.txRangesUnreported || it.allowUnknownTxRange),
                                    rxStatus = "RX mode/filter change failed: ${error.message ?: error.javaClass.simpleName}",
                                )
                            }
                        }
                    }
            },
            RX_RETUNE_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleFrequencyTune() {
        val generation = rxGeneration.get()
        pendingRxRetune = retryScheduler.schedule(
            {
                val snapshot = mutableState.value
                val frequency = snapshot.frequency.toDoubleOrNull()
                if (generation != rxGeneration.get() || !snapshot.rxActive || snapshot.rxBusy ||
                    !snapshot.rxAvailable || snapshot.txActive || snapshot.txBusy ||
                    frequency == null || frequency == activeRxFrequencyHz
                ) return@schedule
                val service = radioService ?: return@schedule
                runCatching { service.tuneReceiver(frequency) }
                    .onSuccess {
                        if (generation == rxGeneration.get()) {
                            activeRxFrequencyHz = frequency
                            activeReceiverConfig = service.receiverConfiguration()
                            if (mutableState.value.frequency == snapshot.frequency) {
                                mutableState.update { it.copy(rxStatus = "${it.mode} audio active • tuned ${formatHz(frequency)}") }
                            }
                        }
                    }
                    .onFailure { error ->
                        if (generation == rxGeneration.get() && mutableState.value.frequency == snapshot.frequency) {
                            val previous = activeRxFrequencyHz
                            mutableState.update {
                                it.copy(
                                    frequency = previous?.toLong()?.toString() ?: it.frequency,
                                    rxStatus = "RX tune failed: ${error.message ?: error.javaClass.simpleName}",
                                )
                            }
                        }
                    }
            },
            RX_RETUNE_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun selectSampleRate(value: Double?) {
        val snapshot = mutableState.value
        val passband = snapshot.bandwidth.toDoubleOrNull()
        if (value != null && value !in snapshot.sampleRateOptions) return
        if (value != null && (passband == null || !SampleRatePolicy.isUsable(value, snapshot.mode, passband))) {
            mutableState.update { it.copy(rxStatus = "That sample rate cannot contain the current passband") }
            return
        }
        mutableState.update {
            it.copy(
                sampleRateAutomatic = value == null,
                sampleRateHz = value ?: it.sampleRateHz,
                rxAvailable = selectedRxFormat != null && (value ?: it.automaticSampleRateHz) != null &&
                    validRxFrequency(it.frequency) &&
                    it.bandwidth.toDoubleOrNull()?.let { passband ->
                        SampleRatePolicy.isUsable((value ?: it.automaticSampleRateHz)!!, it.mode, passband) &&
                            hardwareBandwidthAvailable(passband, it.mode)
                    } == true,
                spectrumSpanHz = it.spectrumSpanHz?.takeIf { span ->
                    span <= SpectrumPolicy.displayRate(value ?: it.automaticSampleRateHz ?: 48_000.0)
                },
                appliedSampleRateHz = null,
            )
        }
        if (value == null) updateAutomaticSampleRate()
    }

    fun discover() {
        val snapshot = mutableState.value
        if (snapshot.loadingProfile || snapshot.rxActive || snapshot.rxBusy ||
            snapshot.txActive || snapshot.txBusy
        ) return
        val port = snapshot.port.toIntOrNull()
        if (snapshot.host.isBlank() || port == null || port !in 1..65535) {
            mutableState.update {
                it.copy(
                    connectionStatus = "Enter a valid server and port",
                    connectionState = RadioConnectionState.FAILED,
                )
            }
            return
        }
        mutableState.update {
            it.copy(
                discovering = true,
                connectionStatus = "Connecting…",
                connectionState = RadioConnectionState.CONNECTING,
                lastError = null,
                txAttemptError = null,
                devices = emptyList(),
                deviceDetails = "",
                additionalDeviceDetails = "",
                activeProfileId = null,
                selectedDeviceLabel = "",
                rxAvailable = false,
                txAvailable = false,
                txFrequencyRanges = emptyList(),
                rxGainRanges = emptyMap(), rxGainValues = emptyMap(),
                rxHardwareAgcSupported = false, rxHardwareAgc = null,
                rxAntennas = emptyList(), rxAntenna = null,
            )
        }
        selectedDevice = null
        selectedHost = null
        selectedRxCapabilities = null
        rxGainOverrides = emptyMap()
        rxHardwareAgcOverride = null
        rxAntennaOverride = null
        selectedTxCapabilities = null
        worker.execute {
            val service = radioService
            if (service == null) {
                mutableState.update {
                    it.copy(
                        discovering = false,
                        connectionStatus = "Radio service is not ready",
                        connectionState = RadioConnectionState.FAILED,
                    )
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
                            connectionState = RadioConnectionState.CONNECTED,
                            lastError = null,
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
                    val failure = RadioFailure.from("Connection failed", error)
                    mutableState.update {
                        it.copy(
                            discovering = false,
                            connectionStatus = failure.displayMessage,
                            connectionState = RadioConnectionState.FAILED,
                            lastError = failure,
                            recentErrors = (it.recentErrors + failure.displayMessage).takeLast(5),
                        )
                    }
                }
        }
    }

    fun inspect(choice: RadioDeviceChoice) {
        val snapshot = mutableState.value
        if (snapshot.loadingProfile || snapshot.rxActive || snapshot.rxBusy ||
            snapshot.txActive || snapshot.txBusy
        ) return
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
                    val failure = RadioFailure.from("Device query failed", error)
                    mutableState.update {
                        it.copy(
                            inspecting = false,
                            connectionStatus = failure.displayMessage,
                            connectionState = RadioConnectionState.FAILED,
                            lastError = failure,
                            recentErrors = (it.recentErrors + failure.displayMessage).takeLast(5),
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
        selectedHost = host.trim()
        selectedDevice = choice.arguments
        val radioPreferenceKey = unknownRangePreferenceKey(host, port, choice.arguments)
        selectedRadioPreferenceKey = radioPreferenceKey
        val rxCapabilities = info.rx
        selectedRxCapabilities = rxCapabilities
        rxGainOverrides = emptyMap()
        rxHardwareAgcOverride = null
        rxAntennaOverride = null
        selectedRxFormat = rxCapabilities.supportedFormat()
        val snapshot = mutableState.value
        val bandwidth = snapshot.bandwidth.toDoubleOrNull() ?: DEFAULT_AM_BANDWIDTH
        val centeredWidth = ModeBandwidthDefaults.centeredRfWidth(snapshot.mode, bandwidth)
        val hardwareBandwidth = rxCapabilities?.let {
            BandwidthPolicy.choose(it.bandwidths, it.bandwidthRanges, centeredWidth)
        }
        val bandwidthAvailable = rxCapabilities == null ||
            !BandwidthPolicy.isReported(rxCapabilities.bandwidths, rxCapabilities.bandwidthRanges) ||
            hardwareBandwidth != null
        val rxRates = rxCapabilities?.let {
            SampleRatePolicy.choose(it.sampleRates, it.sampleRateRanges, centeredWidth)
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
                connectionState = RadioConnectionState.CONNECTED,
                lastError = null,
                deviceDetails = formatDeviceInfo(info),
                additionalDeviceDetails = formatAdditionalDeviceInfo(info),
                selectedDeviceLabel = choice.label,
                txAttemptError = null,
                profileName = choice.label,
                activeProfileId = null,
                sampleRateHz = rxRate,
                automaticSampleRateHz = rxRate,
                sampleRateOptions = rxRates.overrideOptions,
                sampleRateAutomatic = true,
                appliedSampleRateHz = null,
                spectrumSpanHz = it.spectrumSpanHz?.takeIf { span ->
                    rxRate != null && span <= SpectrumPolicy.displayRate(rxRate)
                },
                rxGainRanges = rxCapabilities?.gainRanges.orEmpty(),
                rxGainValues = rxCapabilities?.currentGains.orEmpty(),
                rxHardwareAgcSupported = rxCapabilities?.automaticGain == true,
                rxHardwareAgc = rxCapabilities?.currentGainMode,
                rxAntennas = rxCapabilities?.antennas.orEmpty(),
                rxAntenna = rxCapabilities?.currentAntenna,
                rxAvailable = selectedRxFormat != null && rxRate != null && bandwidthAvailable &&
                    validRxFrequency(it.frequency) && SampleRatePolicy.isUsable(rxRate, it.mode, bandwidth),
                rxStatus = if (!validRxFrequency(it.frequency)) {
                    "Enter a valid RX frequency"
                } else if (it.mode == "NFM" && bandwidth > 38_000.0) {
                    "NFM RF width must be 38 kHz or less"
                } else if (selectedRxFormat != null && rxRate != null && bandwidthAvailable) {
                    "${it.mode} RX ready (${selectedRxFormat!!.format}, ${formatHz(rxRate)}; hardware BW ${hardwareBandwidth?.let(::formatHz) ?: "not reported"})"
                } else {
                    "RX unavailable: no supported rate or hardware bandwidth contains the passband"
                },
                txAvailable = txAvailable && it.mode == "AM",
                txFrequencyRanges = selectedTxCapabilities?.frequencyRanges.orEmpty(),
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
                txAttemptError = null,
                txAvailable = enabled && it.mode == "AM",
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
        val snapshot = mutableState.value
        val bandwidth = snapshot.bandwidth.toDoubleOrNull() ?: return
        val centeredWidth = ModeBandwidthDefaults.centeredRfWidth(snapshot.mode, bandwidth)
        val choice = SampleRatePolicy.choose(
            capabilities.sampleRates,
            capabilities.sampleRateRanges,
            centeredWidth,
        )
        mutableState.update {
            it.copy(
                sampleRateHz = choice.automaticRate,
                automaticSampleRateHz = choice.automaticRate,
                sampleRateOptions = choice.overrideOptions,
                spectrumSpanHz = it.spectrumSpanHz?.takeIf { span ->
                    choice.automaticRate != null && span <= SpectrumPolicy.displayRate(choice.automaticRate)
                },
                rxAvailable = selectedRxFormat != null && choice.automaticRate != null &&
                    validRxFrequency(it.frequency) &&
                    SampleRatePolicy.isUsable(choice.automaticRate, it.mode, bandwidth) &&
                    hardwareBandwidthAvailable(bandwidth, it.mode),
                rxStatus = if (!validRxFrequency(it.frequency)) {
                    "Enter a valid RX frequency"
                } else if (it.mode == "NFM" && bandwidth > 38_000.0) {
                    "NFM RF width must be 38 kHz or less"
                } else if (choice.automaticRate == null || !hardwareBandwidthAvailable(bandwidth, it.mode)) {
                    "No supported RX bandwidth or sample rate contains this passband"
                } else if (!it.rxActive) {
                    "${it.mode} RX ready (${selectedRxFormat!!.format}, ${formatHz(choice.automaticRate)})"
                } else it.rxStatus,
            )
        }
    }

    private fun hardwareBandwidthAvailable(passbandHz: Double, mode: String): Boolean {
        if (!passbandHz.isFinite() || passbandHz <= 0.0) return false
        val capabilities = selectedRxCapabilities ?: return false
        if (!BandwidthPolicy.isReported(capabilities.bandwidths, capabilities.bandwidthRanges)) return true
        return BandwidthPolicy.choose(
            capabilities.bandwidths, capabilities.bandwidthRanges,
            ModeBandwidthDefaults.centeredRfWidth(mode, passbandHz),
        ) != null
    }

    fun startReceiver() {
        pendingRxRetune?.cancel(false)
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
        if (!frequency.isFinite() || frequency <= 0.0 || frequency > Long.MAX_VALUE.toDouble()) {
            mutableState.update { it.copy(rxStatus = "Enter a valid RX frequency") }
            return
        }
        if (!SampleRatePolicy.isUsable(sampleRate, snapshot.mode, bandwidth)) {
            mutableState.update { it.copy(rxAvailable = false,
                rxStatus = if (snapshot.mode == "NFM" && bandwidth > 38_000.0)
                    "NFM RF width must be 38 kHz or less"
                else "Selected sample rate cannot contain this passband") }
            return
        }
        if (!bandwidth.isFinite() || bandwidth <= 0.0 ||
            !hardwareBandwidthAvailable(bandwidth, snapshot.mode)
        ) {
            mutableState.update { it.copy(rxStatus = "No supported hardware bandwidth contains this passband") }
            return
        }
        val capabilities = selectedRxCapabilities ?: return
        val hardwareBandwidth = BandwidthPolicy.forSpectrum(
            capabilities.bandwidths, capabilities.bandwidthRanges,
            ModeBandwidthDefaults.centeredRfWidth(snapshot.mode, bandwidth),
            SpectrumPolicy.displayRate(sampleRate),
        )
        val config = ReceiverConfig(
            endpoint = RadioEndpoint(snapshot.host, selectedPort),
            deviceArguments = device,
            frequencyHz = frequency,
            bandwidthHz = bandwidth,
            mode = snapshot.mode,
            nfmAudioCutoffHz = snapshot.nfmAudioCutoffHz,
            nfmDeemphasisUs = snapshot.nfmDeemphasisUs,
            hardwareBandwidthHz = hardwareBandwidth,
            sampleRate = sampleRate,
            format = format.format,
            fullScale = format.fullScale,
            rxGains = rxGainOverrides,
            rxAntenna = rxAntennaOverride,
            rxHardwareAgc = rxHardwareAgcOverride,
            spectrumSpanHz = snapshot.spectrumSpanHz,
        )
        activeReceiverConfig = config
        val generation = rxGeneration.incrementAndGet()
        mutableState.update {
            it.copy(
                rxStreamFormat = format.format,
                rxRequestedSampleRateHz = sampleRate,
                rxHardwareBandwidthHz = hardwareBandwidth,
                appliedSampleRateHz = null,
                rxAppliedHardwareBandwidthHz = null,
                rxStreamRateHz = null,
                rxSequenceGaps = null,
            )
        }
        radioService?.cancelPendingReceiver()
        openReceiver(config, generation, 0)
    }

    private fun openReceiver(config: ReceiverConfig, generation: Long, retryCount: Int) {
        if (generation != rxGeneration.get()) return
        mutableState.update {
            it.copy(
                rxBusy = true,
                spectrum = null,
                rxStatus = if (retryCount == 0) "Opening RX stream…" else
                    "Reconnecting RX ($retryCount/${RxReconnectPolicy.MAX_RETRIES})…",
                connectionState = if (retryCount == 0) it.connectionState else
                    RadioConnectionState.RECONNECTING,
                connectionStatus = if (retryCount == 0) it.connectionStatus else
                    "Reconnecting to ${config.endpoint.host}…",
            )
        }
        worker.execute {
            if (generation != rxGeneration.get()) return@execute
            val service = radioService
            if (service == null) {
                mutableState.update {
                    it.copy(rxBusy = false, rxStatus = "Radio service is not ready")
                }
                return@execute
            }
            val failed = AtomicBoolean(false)
            runCatching {
                service.startReceiver(
                    config,
                    onStatistics = { stats ->
                        if (generation == rxGeneration.get()) {
                            mutableState.update {
                                it.copy(
                                    rxStatus = buildString {
                                        append("Playing ${it.mode} • %.1f ksps".format(stats.samplesPerSecond / 1_000))
                                        append(" • RMS %.1f dBFS".format(stats.rmsDbfs))
                                        append(" • gaps ${stats.sequenceGaps}")
                                    },
                                    rxStreamRateHz = stats.samplesPerSecond,
                                    rxSequenceGaps = stats.sequenceGaps,
                                )
                            }
                        }
                    },
                    onSpectrum = { frame ->
                        if (generation == rxGeneration.get()) {
                            mutableState.update { current ->
                                val previous = current.spectrum
                                val weight = current.spectrumAveraging
                                val bins = if (previous != null &&
                                    previous.centerFrequencyHz == frame.centerFrequencyHz &&
                                    previous.sampleRateHz == frame.sampleRateHz &&
                                    previous.binsDbfs.size == frame.binsDbfs.size
                                ) FloatArray(frame.binsDbfs.size) { index ->
                                    previous.binsDbfs[index] * (1f - weight) +
                                        frame.binsDbfs[index] * weight
                                } else frame.binsDbfs
                                current.copy(spectrum = frame.copy(binsDbfs = bins))
                            }
                        }
                    },
                    onError = { error ->
                        if (failed.compareAndSet(false, true) && generation == rxGeneration.get()) {
                            handleRxFailure(activeReceiverConfig ?: config, generation, retryCount, error, true)
                        }
                    },
                    isCancelled = { generation != rxGeneration.get() },
                )
            }.onSuccess { applied ->
                if (!failed.get() && generation == rxGeneration.get()) {
                    activeRxFrequencyHz = config.frequencyHz
                    mutableState.update {
                        it.copy(
                            rxActive = true,
                            rxBusy = false,
                            connectionState = RadioConnectionState.CONNECTED,
                            connectionStatus = "Connected to ${config.endpoint.host}:${config.endpoint.port}",
                            lastError = null,
                            appliedSampleRateHz = applied.sampleRate,
                            rxAppliedHardwareBandwidthHz = applied.hardwareBandwidth,
                            spectrumSpanHz = it.spectrumSpanHz?.takeIf { span ->
                                span <= SpectrumPolicy.displayRate(applied.sampleRate)
                            },
                            rxGainValues = it.rxGainValues + config.rxGains,
                            rxHardwareAgc = config.rxHardwareAgc ?: it.rxHardwareAgc,
                            rxAntenna = config.rxAntenna ?: it.rxAntenna,
                            rxStatus = "${config.mode} audio active • applied ${formatHz(applied.sampleRate)}",
                            profileStatus = if (it.activeProfileId != null) {
                                "Receiving with ${it.profileName}"
                            } else it.profileStatus,
                        )
                    }
                }
            }.onFailure { error ->
                if (failed.compareAndSet(false, true) && generation == rxGeneration.get()) {
                    handleRxFailure(config, generation, retryCount, error, retryCount > 0)
                }
            }
        }
    }

    private fun handleRxFailure(
        config: ReceiverConfig,
        generation: Long,
        retryCount: Int,
        error: Throwable,
        streamWasEstablished: Boolean,
    ) {
        val failure = RadioFailure.from("RX failed", error)
        val delay = RxReconnectPolicy.delaySeconds(
            failure.kind, retryCount, streamWasEstablished,
        )
        if (delay != null) {
            val nextRetry = retryCount + 1
            mutableState.update {
                it.copy(
                    rxActive = false,
                    rxBusy = false,
                    spectrum = null,
                    connectionState = RadioConnectionState.RECONNECTING,
                    connectionStatus = "Reconnecting to ${config.endpoint.host}…",
                    lastError = failure,
                    recentErrors = (it.recentErrors + failure.displayMessage).takeLast(5),
                    observedRxOverflows = it.observedRxOverflows +
                        if (error.isSoapyStreamCode(-4)) 1 else 0,
                    rxStatus = "${failure.displayMessage}; retrying RX ($nextRetry/${RxReconnectPolicy.MAX_RETRIES})",
                )
            }
            retryScheduler.schedule(
                { if (generation == rxGeneration.get()) openReceiver(config, generation, nextRetry) },
                delay,
                TimeUnit.SECONDS,
            )
        } else {
            mutableState.update {
                it.copy(
                    rxActive = false,
                    rxBusy = false,
                    spectrum = null,
                    connectionState = RadioConnectionState.FAILED,
                    connectionStatus = "RX connection failed",
                    lastError = failure,
                    recentErrors = (it.recentErrors + failure.displayMessage).takeLast(5),
                    observedRxOverflows = it.observedRxOverflows +
                        if (error.isSoapyStreamCode(-4)) 1 else 0,
                    rxStatus = failure.displayMessage,
                )
            }
        }
    }

    fun stopReceiver() {
        pendingRxRetune?.cancel(false)
        activeRxFrequencyHz = null
        activeReceiverConfig = null
        val generation = rxGeneration.incrementAndGet()
        radioService?.cancelPendingReceiver()
        mutableState.update { it.copy(rxBusy = true, rxStatus = "Stopping RX…") }
        closeReceiverAsync(generation)
    }

    private fun closeReceiverAsync(generation: Long) {
        worker.execute {
            if (generation != rxGeneration.get()) return@execute
            runCatching { radioService?.stopReceiver() }
            if (generation == rxGeneration.get()) {
                mutableState.update {
                    it.copy(
                        rxActive = false,
                        rxBusy = false,
                        spectrum = null,
                        rxStatus = "RX stopped",
                        connectionState = if (it.connectionState == RadioConnectionState.RECONNECTING)
                            RadioConnectionState.DISCONNECTED else it.connectionState,
                        connectionStatus = if (it.connectionState == RadioConnectionState.RECONNECTING)
                            "RX retry cancelled" else it.connectionStatus,
                    )
                }
            }
        }
    }

    fun validateTransmit(): String? {
        val snapshot = mutableState.value
        return TxControlPolicy.unavailableReason(
            snapshot.mode, snapshot.txAvailable, snapshot.txStatus,
            snapshot.frequency, snapshot.txFrequencyRanges,
        )
    }

    fun reportTxAttemptError(message: String) {
        val generation = txErrorGeneration.incrementAndGet()
        mutableState.update { it.copy(txAttemptError = message) }
        retryScheduler.schedule({
            if (generation == txErrorGeneration.get()) {
                mutableState.update { it.copy(txAttemptError = null) }
            }
        }, 5, TimeUnit.SECONDS)
    }

    fun startTransmitter() {
        val validation = validateTransmit()
        if (validation != null) {
            reportTxAttemptError(validation)
            return
        }
        val snapshot = mutableState.value
        val device = selectedDevice ?: return
        val frequency = snapshot.frequency.toDouble()
        val format = selectedTxFormat ?: return
        val sampleRate = selectedTxSampleRate ?: return
        rxGeneration.incrementAndGet() // A failed or stopped TX must never revive an old RX retry.
        radioService?.cancelPendingReceiver()
        resumeRxAfterTx = snapshot.rxActive
        mutableState.update {
            it.copy(txBusy = true, txAttemptError = null,
                txStatus = "Stopping RX and opening AM TX…")
        }
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
                        val failure = RadioFailure.from("AM TX failed", error)
                        mutableState.update {
                            it.copy(
                                lastError = failure,
                                recentErrors = (it.recentErrors + failure.displayMessage).takeLast(5),
                                observedTxUnderflows = it.observedTxUnderflows +
                                    if (error.isSoapyStreamCode(-7)) 1 else 0,
                            )
                        }
                        stopTransmitter(failure.displayMessage)
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
                val failure = RadioFailure.from("Could not start AM TX", error)
                val resumeReceiver = resumeRxAfterTx
                resumeRxAfterTx = false
                mutableState.update {
                    it.copy(
                        txActive = false,
                        txBusy = false,
                        lastError = failure,
                        recentErrors = (it.recentErrors + failure.displayMessage).takeLast(5),
                        observedTxUnderflows = it.observedTxUnderflows +
                            if (error.isSoapyStreamCode(-7)) 1 else 0,
                        txStatus = failure.displayMessage,
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
        pendingRxRetune?.cancel(false)
        rxGeneration.incrementAndGet()
        radioService?.cancelPendingReceiver()
        resumeRxAfterTx = false
        radioService?.setStateListener(null)
        radioService?.setExternalStopListener(null)
        radioService = null
        worker.shutdownNow()
        retryScheduler.shutdownNow()
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
    info.notReported.filterNot { it == "device settings" || it == "device sensors" }
        .takeIf { it.isNotEmpty() }
        ?.let { append("\nNot reported: ${it.joinToString()}") }
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
    capabilities.notReported.filterNot { it == "channel settings" || it == "channel sensors" }
        .takeIf { it.isNotEmpty() }
        ?.let { append("\nNot reported: ${it.joinToString()}") }
}

private fun formatAdditionalDeviceInfo(info: RadioDeviceCapabilities) = buildString {
    append("Driver settings and sensors")
    appendSettings("Device settings", info.settings, "device settings" in info.notReported)
    appendSensors("Device sensors", info.sensors, "device sensors" in info.notReported)
    info.allRx.forEachIndexed { channel, capabilities ->
        append("\n\nRX $channel")
        appendSettings("Channel settings", capabilities.settings, "channel settings" in capabilities.notReported)
        appendSensors("Channel sensors", capabilities.sensors, "channel sensors" in capabilities.notReported)
    }
    info.allTx.forEachIndexed { channel, capabilities ->
        append("\n\nTX $channel")
        appendSettings("Channel settings", capabilities.settings, "channel settings" in capabilities.notReported)
        appendSensors("Channel sensors", capabilities.sensors, "channel sensors" in capabilities.notReported)
    }
}

private fun StringBuilder.appendSettings(
    label: String,
    settings: List<RadioSetting>,
    unsupported: Boolean,
) {
    append("\n$label: ${if (unsupported) "not reported" else if (settings.isEmpty()) "none" else ""}")
    settings.forEach { setting ->
        append("\n  ${setting.info.name.ifBlank { setting.info.key }} (${setting.info.key})")
        appendArgumentDetails(setting.info)
        append("; current ${setting.currentValue ?: "not reported"}")
    }
}

private fun StringBuilder.appendSensors(
    label: String,
    sensors: List<RadioSensor>,
    unsupported: Boolean,
) {
    append("\n$label: ${if (unsupported) "not reported" else if (sensors.isEmpty()) "none" else ""}")
    sensors.forEach { sensor ->
        append("\n  ${sensor.info?.name?.takeIf { it.isNotBlank() } ?: sensor.key} (${sensor.key})")
        sensor.info?.let { appendArgumentDetails(it) }
        append("; current ${sensor.currentValue ?: "not reported"}")
        sensor.info?.units?.takeIf { it.isNotBlank() }?.let { append(" $it") }
    }
}

private fun StringBuilder.appendArgumentDetails(info: RadioArgumentInfo) {
    val type = listOf("boolean", "integer", "float", "string").getOrNull(info.type)
        ?: "type ${info.type}"
    append("; $type")
    if (info.options.isNotEmpty()) append("; options ${info.options.joinToString()}")
    else if (info.range.maximum > info.range.minimum) {
        append("; range ${info.range.minimum}–${info.range.maximum}")
        if (info.range.step > 0.0) append(" step ${info.range.step}")
    }
    if (info.value.isNotEmpty()) append("; default ${info.value}")
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

private val DEFAULT_AM_BANDWIDTH = ModeBandwidthDefaults.AM_HZ.toDouble()
private fun validRxFrequency(text: String): Boolean = text.toDoubleOrNull()?.let {
    it.isFinite() && it > 0.0 && it <= Long.MAX_VALUE.toDouble()
} == true
private const val RX_RETUNE_DELAY_MS = 600L
private val TUNING_STEPS_HZ = setOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0)
private val NFM_AUDIO_CUTOFFS_HZ = setOf(2_500.0, 3_000.0, 4_000.0)
private val NFM_DEEMPHASIS_US = setOf(0, 50, 75)
