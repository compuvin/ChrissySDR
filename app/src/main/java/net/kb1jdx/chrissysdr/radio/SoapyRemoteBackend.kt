package com.kb1jdx.chrissysdr.radio

import com.kb1jdx.chrissysdr.soapyremote.SoapyChannelCapabilities
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteClient
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteRxSession
import com.kb1jdx.chrissysdr.soapyremote.SoapyRemoteTxSession

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
        )
    }

    override fun openReceiver(config: ReceiverConfig): RadioReceiver {
        val session = SoapyRemoteRxSession.open(
            config.endpoint.host,
            config.endpoint.port,
            config.deviceArguments,
            config.frequencyHz,
            config.bandwidthHz,
            config.sampleRate,
            config.format,
        )
        return object : RadioReceiver {
            override val appliedSampleRate = session.inputSampleRate
            override fun start(
                onStatistics: (ReceiverStatistics) -> Unit,
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
    antennas = antennas,
    gains = gains,
    gainRanges = gainRanges.mapValues { (_, range) ->
        RadioRange(range.minimum, range.maximum, range.step)
    },
    automaticGain = automaticGain,
    fullDuplex = fullDuplex,
    frequencyRanges = frequencyRanges.map { RadioRange(it.minimum, it.maximum, it.step) },
    sampleRates = sampleRates,
    sampleRateRanges = sampleRateRanges.map { RadioRange(it.minimum, it.maximum, it.step) },
    bandwidths = bandwidths,
    bandwidthRanges = bandwidthRanges.map { RadioRange(it.minimum, it.maximum, it.step) },
    notReported = notReported,
)
