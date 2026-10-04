package com.kb1jdx.chrissysdr.radio

import com.kb1jdx.chrissysdr.soapyremote.SoapyChannelCapabilities
import com.kb1jdx.chrissysdr.soapyremote.SoapyArgInfo
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteClient
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteRxSession
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteTxSession
import com.kb1jdx.chrissysdr.soapyremote.SoapySensor
import com.kb1jdx.chrissysdr.soapyremote.SoapySetting

class SoapyRemoteBackend(
    private val client: SoapyRemoteClient = SoapyRemoteClient(),
) : RadioBackend {
    override fun discover(endpoint: RadioEndpoint): RadioDiscovery {
        val result = client.discover(endpoint.host, endpoint.port)
        return RadioDiscovery(
            serverId = result.serverId,
            devices = result.devices.mapIndexed { index, arguments ->
                RadioDeviceDescriptor(
                    label = arguments["label"] ?: arguments["driver"] ?: "Device ${index + 1}",
                    arguments = arguments,
                )
            },
        )
    }

    override fun inspect(
        endpoint: RadioEndpoint,
        deviceArguments: Map<String, String>,
    ): RadioDeviceCapabilities {
        val info = client.inspect(endpoint.host, endpoint.port, deviceArguments)
        return RadioDeviceCapabilities(
            driverKey = info.driverKey,
            hardwareKey = info.hardwareKey,
            metadata = info.hardwareInfo,
            rxChannels = info.rxChannels,
            txChannels = info.txChannels,
            rx = info.rxCapabilities?.toRadioCapabilities(),
            tx = info.txCapabilities?.toRadioCapabilities(),
            allRx = info.allRxCapabilities.map { it.toRadioCapabilities() },
            allTx = info.allTxCapabilities.map { it.toRadioCapabilities() },
            settings = info.settings.map { it.toRadioSetting() },
            sensors = info.sensors.map { it.toRadioSensor() },
            notReported = info.notReported,
        )
    }

    override fun openReceiver(config: ReceiverConfig, cancellation: RadioOpenCancellation): RadioReceiver {
        val session = SoapyRemoteRxSession.open(
            config.endpoint.host,
            config.endpoint.port,
            config.deviceArguments,
            config.frequencyHz,
            config.bandwidthHz,
            config.hardwareBandwidthHz,
            config.sampleRate,
            config.format,
            config.fullScale,
            config.mode,
            config.nfmAudioCutoffHz,
            config.nfmDeemphasisUs,
            config.rxGains,
            config.rxAntenna,
            config.rxHardwareAgc,
            config.spectrumSpanHz,
            cancellation,
        )
        return object : RadioReceiver {
            override val appliedSampleRate = session.inputSampleRate
            override val appliedHardwareBandwidth = session.appliedHardwareBandwidthHz
            override fun setAudioVolume(volume: Float) = session.setAudioVolume(volume)
            override fun tune(frequencyHz: Double) = session.tune(frequencyHz)
            override fun setSpectrumSpan(spanHz: Double?) = session.setSpectrumSpan(spanHz)
            override fun reconfigure(settings: ReceiverDspSettings) = session.reconfigure(settings)
            override fun start(
                onStatistics: (ReceiverStatistics) -> Unit,
                onSpectrum: (SpectrumFrame) -> Unit,
                onError: (Throwable) -> Unit,
            ) = session.start(
                onStatistics = { stats ->
                    onStatistics(
                        ReceiverStatistics(
                            stats.totalSamples,
                            stats.samplesPerSecond,
                            stats.rmsDbfs,
                            stats.peakDbfs,
                            stats.sequenceGaps,
                        ),
                    )
                },
                onSpectrum = { bins ->
                    onSpectrum(SpectrumFrame(bins, session.spectrumSampleRate, session.hardwareCenterHz))
                },
                onError = onError,
            )

            override fun close() = session.close()
        }
    }

    override fun openTransmitter(config: TransmitterConfig): RadioTransmitter {
        val session = SoapyRemoteTxSession.open(
            config.endpoint.host,
            config.endpoint.port,
            config.deviceArguments,
            config.frequencyHz,
            config.sampleRate,
            config.format,
            config.fullScale,
        )
        return object : RadioTransmitter {
            override val appliedSampleRate = session.outputSampleRate
            override fun start(
                onStatistics: (TransmitterStatistics) -> Unit,
                onStopped: () -> Unit,
                onError: (Throwable) -> Unit,
            ) = session.start(
                onStatistics = { stats ->
                    onStatistics(
                        TransmitterStatistics(
                            stats.totalSamples,
                            stats.samplesPerSecond,
                            stats.microphonePeak,
                            stats.secondsRemaining,
                        ),
                    )
                },
                onStopped = onStopped,
                onError = onError,
            )

            override fun close() = session.close()
        }
    }
}

private fun SoapyChannelCapabilities.toRadioCapabilities() = RadioChannelCapabilities(
    formats = formats,
    nativeFormat = nativeFormat,
    nativeFullScale = nativeFullScale,
    streamArgs = streamArgs.map { it.toRadioArgumentInfo() },
    antennas = antennas,
    gains = gains,
    gainRanges = gainRanges.mapValues { (_, range) ->
        RadioRange(range.minimum, range.maximum, range.step)
    },
    currentGains = currentGains,
    currentAntenna = currentAntenna,
    currentGainMode = currentGainMode,
    automaticGain = automaticGain,
    fullDuplex = fullDuplex,
    frequencyRanges = frequencyRanges.map { RadioRange(it.minimum, it.maximum, it.step) },
    sampleRates = sampleRates,
    sampleRateRanges = sampleRateRanges.map { RadioRange(it.minimum, it.maximum, it.step) },
    bandwidths = bandwidths,
    bandwidthRanges = bandwidthRanges.map { RadioRange(it.minimum, it.maximum, it.step) },
    notReported = notReported,
    settings = settings.map { it.toRadioSetting() },
    sensors = sensors.map { it.toRadioSensor() },
)

private fun SoapyArgInfo.toRadioArgumentInfo() = RadioArgumentInfo(
    key = key,
    value = value,
    name = name,
    description = description,
    units = units,
    type = type,
    range = RadioRange(range.minimum, range.maximum, range.step),
    options = options,
    optionNames = optionNames,
)

private fun SoapySetting.toRadioSetting() = RadioSetting(info.toRadioArgumentInfo(), currentValue)

private fun SoapySensor.toRadioSensor() = RadioSensor(key, info?.toRadioArgumentInfo(), currentValue)
