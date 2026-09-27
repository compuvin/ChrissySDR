package net.kb1jdx.chrissysdr

import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import android.text.InputType
import android.view.ViewGroup
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kb1jdx.chrissysdr.soapyremote.SoapyRemoteClient
import net.kb1jdx.chrissysdr.soapyremote.SoapyRemoteDeviceInfo
import net.kb1jdx.chrissysdr.soapyremote.SoapyChannelCapabilities
import net.kb1jdx.chrissysdr.soapyremote.SoapyRange
import net.kb1jdx.chrissysdr.soapyremote.SoapyRemoteRxSession

class MainActivity : Activity() {
    private val client = SoapyRemoteClient()
    private val localNetworkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    private lateinit var status: TextView
    private lateinit var rxControls: LinearLayout
    private lateinit var rxStatus: TextView
    private lateinit var startRx: Button
    private lateinit var stopRx: Button
    private lateinit var sampleRate: EditText
    private lateinit var rxHeading: TextView
    private var selectedHost: String? = null
    private var selectedPort: Int = SoapyRemoteClient.DEFAULT_PORT
    private var selectedDevice: Map<String, String>? = null
    private var selectedFormat: String? = null
    @Volatile private var rxSession: SoapyRemoteRxSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = EditText(this).apply {
            hint = "SoapyRemote server address"
            setText("192.168.1.100")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val port = EditText(this).apply {
            hint = "Port"
            setText(SoapyRemoteClient.DEFAULT_PORT.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        status = TextView(this).apply {
            text = "Not connected"
            textSize = 17f
            setPadding(0, 24, 0, 0)
        }
        val deviceList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val frequency = EditText(this).apply {
            hint = "RX frequency (Hz)"
            setText("10000000")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val bandwidth = EditText(this).apply {
            hint = "RX bandwidth (Hz)"
            setText("12000")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        sampleRate = EditText(this).apply {
            hint = "RX sample rate (Hz)"
            setText("48000")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        rxStatus = TextView(this).apply {
            text = "RX stopped"
            setPadding(0, 16, 0, 0)
        }
        startRx = Button(this).apply {
            text = "Start RX"
            setOnClickListener {
                val frequencyHz = frequency.text.toString().toDoubleOrNull()
                val bandwidthHz = bandwidth.text.toString().toDoubleOrNull()
                val sampleRateHz = sampleRate.text.toString().toDoubleOrNull()
                val hostValue = selectedHost
                val deviceValue = selectedDevice
                val formatValue = selectedFormat
                if (frequencyHz == null || bandwidthHz == null || sampleRateHz == null ||
                    hostValue == null || deviceValue == null || formatValue == null
                ) {
                    rxStatus.text = "Select a device and enter valid RX values"
                    return@setOnClickListener
                }
                startReceiver(
                    hostValue, selectedPort, deviceValue, frequencyHz, bandwidthHz,
                    sampleRateHz, formatValue,
                )
            }
        }
        stopRx = Button(this).apply {
            text = "Stop RX"
            isEnabled = false
            setOnClickListener { stopReceiver() }
        }
        rxControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            rxHeading = TextView(context).apply {
                text = "Live AM RX"
                textSize = 20f
                setPadding(0, 32, 0, 8)
            }
            addView(rxHeading)
            addView(frequency, matchWidth())
            addView(bandwidth, matchWidth())
            addView(sampleRate, matchWidth())
            addView(startRx, matchWidth())
            addView(stopRx, matchWidth())
            addView(rxStatus, matchWidth())
        }
        val connect = Button(this).apply { text = "Discover devices" }
        connect.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(localNetworkPermission) != PackageManager.PERMISSION_GRANTED
            ) {
                status.text = "Local-network permission is required to reach the radio server."
                requestPermissions(arrayOf(localNetworkPermission), LOCAL_NETWORK_REQUEST)
                return@setOnClickListener
            }
            connect.isEnabled = false
            status.text = "Connecting…"
            deviceList.removeAllViews()
            val requestedHost = host.text.toString()
            val requestedPort = port.text.toString().toIntOrNull()
            if (requestedPort == null) {
                connect.isEnabled = true
                status.text = "Port must be a number"
                return@setOnClickListener
            }
            Thread {
                val result = runCatching {
                    client.discover(requestedHost, requestedPort)
                }
                runOnUiThread {
                    connect.isEnabled = true
                    result.onSuccess { discovery ->
                        status.text = "Connected\nServer: ${discovery.serverId}\nDevices: ${discovery.devices.size}"
                        discovery.devices.forEachIndexed { index, device ->
                            deviceList.addView(Button(this).apply {
                                val label = device["label"] ?: device["driver"] ?: "Unnamed device"
                                text = "${index + 1}. $label"
                                setOnClickListener {
                                    inspectDevice(requestedHost, requestedPort, device, this)
                                }
                            }, matchWidth())
                        }
                    }.onFailure { error ->
                        status.text = "Connection failed\n${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }.start()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
            addView(TextView(context).apply { text = "ChrissySDR"; textSize = 30f })
            addView(TextView(context).apply {
                text = "SoapyRemote device discovery"
                textSize = 18f
                setPadding(0, 8, 0, 28)
            })
            addView(host, matchWidth())
            addView(port, matchWidth())
            addView(connect, matchWidth())
            addView(status, matchWidth())
            addView(deviceList, matchWidth())
            addView(rxControls, matchWidth())
            addView(TextView(context).apply {
                text = "ChrissySDR ${appVersion()}"
                textSize = 12f
                alpha = 0.65f
                setPadding(0, 48, 0, 8)
            }, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun inspectDevice(
        host: String,
        port: Int,
        args: Map<String, String>,
        button: Button,
    ) {
        button.isEnabled = false
        status.text = "Opening ${button.text}…"
        Thread {
            val result = runCatching { client.inspect(host, port, args) }
            runOnUiThread {
                button.isEnabled = true
                status.text = result.fold(
                    onSuccess = {
                        selectedHost = host
                        selectedPort = port
                        selectedDevice = args
                        configureReceiver(it)
                        formatDeviceInfo(it) + receiverAvailability(it)
                    },
                    onFailure = { "Device query failed\n${it.message ?: it.javaClass.simpleName}" },
                )
            }
        }.start()
    }

    private fun startReceiver(
        host: String,
        port: Int,
        args: Map<String, String>,
        frequencyHz: Double,
        bandwidthHz: Double,
        sampleRateHz: Double,
        format: String,
    ) {
        startRx.isEnabled = false
        rxStatus.text = "Opening device and RX stream…"
        Thread {
            val result = runCatching {
                val session = SoapyRemoteRxSession.open(
                    host, port, args, frequencyHz, bandwidthHz, sampleRateHz, format,
                )
                try {
                    session.start(
                        onStatistics = { stats ->
                            runOnUiThread {
                                rxStatus.text = buildString {
                                    append("Playing AM audio ($format, ${formatHz(sampleRateHz)} I/Q)")
                                    append("\nRate: %.1f samples/s".format(stats.samplesPerSecond))
                                    append("\nTotal: ${stats.totalSamples} samples")
                                    append("\nRMS: %.1f dBFS".format(stats.rmsDbfs))
                                    append("\nPeak: %.1f dBFS".format(stats.peakDbfs))
                                    append("\nSequence gaps: ${stats.sequenceGaps}")
                                }
                            }
                        },
                        onError = { error ->
                            runOnUiThread {
                                rxStatus.text = "RX stream failed\n${error.message ?: error.javaClass.simpleName}"
                                startRx.isEnabled = true
                                stopRx.isEnabled = false
                            }
                        },
                    )
                    rxSession = session
                    session
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }
            runOnUiThread {
                result.onSuccess {
                    rxStatus.text = "AM audio active; waiting for samples…"
                    stopRx.isEnabled = true
                }.onFailure {
                    rxSession = null
                    startRx.isEnabled = true
                    stopRx.isEnabled = false
                    rxStatus.text = "Could not start RX\n${it.message ?: it.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun stopReceiver() {
        stopRx.isEnabled = false
        rxStatus.text = "Stopping RX…"
        val session = rxSession
        rxSession = null
        Thread {
            runCatching { session?.close() }
            runOnUiThread {
                startRx.isEnabled = true
                rxStatus.text = "RX stopped"
            }
        }.start()
    }

    override fun onDestroy() {
        val session = rxSession
        rxSession = null
        if (session != null) Thread { session.close() }.start()
        super.onDestroy()
    }

    private fun formatDeviceInfo(info: SoapyRemoteDeviceInfo) = buildString {
        append("Device opened and closed successfully")
        append("\nDriver: ${info.driverKey}")
        append("\nHardware: ${info.hardwareKey}")
        append("\nRX channels: ${info.rxChannels}")
        append("\nTX channels: ${info.txChannels}")
        info.rxCapabilities?.let { append(formatCapabilities("RX 0", it)) }
        info.txCapabilities?.let { append(formatCapabilities("TX 0", it)) }
        if (info.hardwareInfo.isNotEmpty()) {
            append("\n\nHardware information")
            info.hardwareInfo.forEach { (key, value) -> append("\n$key: $value") }
        }
    }

    private fun configureReceiver(info: SoapyRemoteDeviceInfo) {
        val capabilities = info.rxCapabilities
        val format = when {
            capabilities == null -> null
            "CS16" in capabilities.formats -> "CS16"
            "CF32" in capabilities.formats -> "CF32"
            else -> null
        }
        val rate = capabilities?.preferredSampleRate()
        selectedFormat = format
        if (format != null && rate != null) {
            sampleRate.setText(rate.toPlainRate())
            rxHeading.text = "Live AM RX ($format over TCP)"
            rxControls.visibility = View.VISIBLE
        } else {
            rxControls.visibility = View.GONE
            selectedFormat = null
        }
    }

    private fun receiverAvailability(info: SoapyRemoteDeviceInfo): String {
        val capabilities = info.rxCapabilities ?: return "\n\nAM RX unavailable: no RX channel."
        if (capabilities.formats.none { it == "CS16" || it == "CF32" }) {
            return "\n\nAM RX unavailable: device does not report CS16 or CF32."
        }
        if (capabilities.preferredSampleRate() == null) {
            return "\n\nAM RX unavailable: device does not report a usable sample rate."
        }
        return ""
    }

    private fun SoapyChannelCapabilities.preferredSampleRate(): Double? {
        val discrete = sampleRates.filter { it >= MIN_RX_SAMPLE_RATE }.minOrNull()
        if (discrete != null) return discrete
        return sampleRateRanges
            .map { maxOf(it.minimum, MIN_RX_SAMPLE_RATE) }
            .filterIndexed { index, candidate -> candidate <= sampleRateRanges[index].maximum }
            .minOrNull()
    }

    private fun Double.toPlainRate(): String =
        if (this % 1.0 == 0.0) toLong().toString() else toString()

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

    private fun formatHz(value: Double): String = when {
        kotlin.math.abs(value) >= 1_000_000 -> "%.6g MHz".format(value / 1_000_000)
        kotlin.math.abs(value) >= 1_000 -> "%.6g kHz".format(value / 1_000)
        else -> "%.6g Hz".format(value)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCAL_NETWORK_REQUEST) {
            status.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                "Local-network access granted. Tap Discover devices again."
            } else {
                "Local-network access denied. Enable Nearby devices in Android app settings."
            }
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    @Suppress("DEPRECATION")
    private fun appVersion(): String = packageManager
        .getPackageInfo(packageName, 0)
        .versionName ?: "unknown"

    companion object {
        private const val LOCAL_NETWORK_REQUEST = 100
        private const val MIN_RX_SAMPLE_RATE = 8_000.0
    }
}
