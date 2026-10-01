package com.kb1jdx.chrissysdr

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kb1jdx.chrissysdr.radio.RadioService
import com.kb1jdx.chrissysdr.radio.RadioConnectionState

class MainActivity : ComponentActivity() {
    private val radio: RadioViewModel by viewModels()
    private var serviceBound = false
    private val radioConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? RadioService.LocalBinder)?.service ?: return
            serviceBound = true
            radio.attachService(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            radio.serviceDisconnected()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, RadioService::class.java),
            radioConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        if (serviceBound) {
            unbindService(radioConnection)
            serviceBound = false
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChrissySdrTheme {
                val state by radio.state.collectAsStateWithLifecycle()
                val microphonePermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    radio.setPermissionMessage(
                        if (granted) "Microphone ready for AM transmit"
                        else "Microphone access denied; AM transmit cannot start",
                    )
                }
                val localNetworkPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) radio.discover()
                    else radio.setPermissionMessage("Local-network access was denied")
                }
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                LaunchedEffect(state.txAvailable) {
                    if (state.txAvailable &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                LaunchedEffect(state.rxAvailable) {
                    if (state.rxAvailable && Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                RadioScreen(
                    state = state,
                    version = appVersion(),
                    onHostChanged = radio::setHost,
                    onPortChanged = radio::setPort,
                    onFrequencyChanged = radio::setFrequency,
                    onBandwidthChanged = radio::setBandwidth,
                    onSampleRateChanged = radio::selectSampleRate,
                    onAllowUnknownTxRange = radio::setAllowUnknownTxRange,
                    onDiscover = {
                        if (Build.VERSION.SDK_INT >= 37 &&
                            checkSelfPermission(LOCAL_NETWORK_PERMISSION) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
                        } else {
                            radio.discover()
                        }
                    },
                    onInspect = radio::inspect,
                    onStartRx = radio::startReceiver,
                    onStopRx = radio::stopReceiver,
                    onTxPressed = {
                        if (state.txActive) {
                            radio.stopTransmitter()
                            true
                        } else if (
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else {
                            val error = radio.validateTransmit()
                            if (error != null) {
                                radio.setPermissionMessage(error)
                                false
                            } else {
                                true
                            }
                        }
                    },
                    onConfirmTx = radio::startTransmitter,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = packageManager
        .getPackageInfo(packageName, 0)
        .versionName ?: "unknown"

    companion object {
        private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioScreen(
    state: RadioUiState,
    version: String,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onFrequencyChanged: (String) -> Unit,
    onBandwidthChanged: (String) -> Unit,
    onSampleRateChanged: (Double?) -> Unit,
    onAllowUnknownTxRange: (Boolean) -> Unit,
    onDiscover: () -> Unit,
    onInspect: (RadioDeviceChoice) -> Unit,
    onStartRx: () -> Unit,
    onStopRx: () -> Unit,
    onTxPressed: () -> Boolean,
    onConfirmTx: () -> Unit,
) {
    var settingsVisible by rememberSaveable { mutableStateOf(state.devices.isEmpty()) }
    var showTransmitConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = RadioBackground,
        topBar = {
            RadioHeader(
                connectionStatus = state.connectionStatus,
                onSettings = { settingsVisible = true },
            )
        },
        bottomBar = {
            OperatingBar(
                frequency = state.frequency.toDoubleOrNull(),
                mode = state.mode,
                transmitting = state.txActive,
                txBusy = state.txBusy,
                txAvailable = state.txAvailable,
                onOpenSettings = { settingsVisible = true },
                onTx = {
                    if (state.txActive) {
                        onTxPressed()
                    } else if (onTxPressed()) {
                        showTransmitConfirmation = true
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SpectrumPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                centerFrequency = state.frequency.toDoubleOrNull(),
                active = state.rxActive,
            )
            Spacer(Modifier.height(12.dp))
            StatusPanel(state)
        }
    }

    if (settingsVisible) {
        ModalBottomSheet(
            onDismissRequest = { settingsVisible = false },
            containerColor = RadioPanel,
        ) {
            SettingsSheet(
                state = state,
                version = version,
                onHostChanged = onHostChanged,
                onPortChanged = onPortChanged,
                onFrequencyChanged = onFrequencyChanged,
                onBandwidthChanged = onBandwidthChanged,
                onSampleRateChanged = onSampleRateChanged,
                onAllowUnknownTxRange = onAllowUnknownTxRange,
                onDiscover = onDiscover,
                onInspect = onInspect,
                onStartRx = onStartRx,
                onStopRx = onStopRx,
            )
        }
    }

    if (showTransmitConfirmation) {
        AlertDialog(
            onDismissRequest = { showTransmitConfirmation = false },
            title = { Text("Confirm AM transmit") },
            text = {
                Text(
                    "Transmit microphone audio on ${displayFrequency(state.frequency.toDoubleOrNull())} " +
                        "for up to 30 seconds? Use a dummy load or controlled test setup and " +
                        "operate only within your license privileges.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showTransmitConfirmation = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTransmitConfirmation = false
                        onConfirmTx()
                    },
                ) { Text("Start TX") }
            },
        )
    }
}

@Composable
private fun RadioHeader(connectionStatus: String, onSettings: () -> Unit) {
    Surface(
        modifier = Modifier.statusBarsPadding(),
        color = RadioPanel,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ChrissySDR", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    connectionStatus,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onSettings) { Text("SETTINGS") }
        }
    }
}

@Composable
private fun SpectrumPlaceholder(
    modifier: Modifier,
    centerFrequency: Double?,
    active: Boolean,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SpectrumBackground),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val gridColor = Color(0x2638D6C7)
                repeat(9) { index ->
                    val x = size.width * index / 8f
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                repeat(7) { index ->
                    val y = size.height * index / 6f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                }
                val path = Path()
                val baseline = size.height * 0.7f
                path.moveTo(0f, baseline)
                repeat(96) { index ->
                    val x = size.width * index / 95f
                    val center = kotlin.math.abs(index - 48) / 48f
                    val signal = if (active) {
                        kotlin.math.exp(-center * 12f) * size.height * 0.38f
                    } else 0f
                    val ripple = kotlin.math.sin(index * 1.7f) * size.height * 0.012f
                    path.lineTo(x, baseline - signal + ripple)
                }
                drawPath(path, SpectrumTrace, style = Stroke(3f, cap = StrokeCap.Round))
                drawLine(
                    color = FrequencyMarker,
                    start = Offset(size.width / 2, 0f),
                    end = Offset(size.width / 2, size.height),
                    strokeWidth = 2f,
                )
            }
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
            ) {
                Text("SPECTRUM", color = SpectrumTrace, fontWeight = FontWeight.Bold)
                Text(
                    if (active) "RX stream active • FFT coming next" else "Display preview • start RX for audio",
                    color = Color(0xFF91A8A5),
                    fontSize = 12.sp,
                )
            }
            Text(
                displayFrequency(centerFrequency),
                modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun StatusPanel(state: RadioUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RadioPanel),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                when {
                    state.txActive -> state.txStatus
                    state.rxActive || state.rxBusy -> state.rxStatus
                    else -> "Ready • ${state.rxStatus}"
                },
                color = if (state.txActive) TxRed else MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = if (state.txActive) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun OperatingBar(
    frequency: Double?,
    mode: String,
    transmitting: Boolean,
    txBusy: Boolean,
    txAvailable: Boolean,
    onOpenSettings: () -> Unit,
    onTx: () -> Unit,
) {
    Surface(color = RadioPanel, tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onOpenSettings),
                horizontalAlignment = Alignment.Start,
            ) {
                Text("FREQUENCY", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    displayFrequency(frequency),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = onTx,
                enabled = (txAvailable || transmitting) && !txBusy,
                modifier = Modifier.size(70.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (transmitting) Color.White else TxRed,
                    contentColor = if (transmitting) TxRed else Color.White,
                    disabledContainerColor = Color(0xFF4B5556),
                ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (transmitting) "STOP" else "TX", fontWeight = FontWeight.Black)
            }
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onOpenSettings),
                horizontalAlignment = Alignment.End,
            ) {
                Text("MODE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(mode, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SpectrumTrace)
                Text("Pull up controls", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    state: RadioUiState,
    version: String,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onFrequencyChanged: (String) -> Unit,
    onBandwidthChanged: (String) -> Unit,
    onSampleRateChanged: (Double?) -> Unit,
    onAllowUnknownTxRange: (Boolean) -> Unit,
    onDiscover: () -> Unit,
    onInspect: (RadioDeviceChoice) -> Unit,
    onStartRx: () -> Unit,
    onStopRx: () -> Unit,
) {
    var showUnknownRangeWarning by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text("Radio controls", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Frequency and mode remain visible in the operating bar.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = state.frequency,
            onValueChange = onFrequencyChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Frequency (Hz)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.bandwidth,
                onValueChange = onBandwidthChanged,
                modifier = Modifier.weight(1f),
                label = { Text("AM passband (Hz)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
            SampleRateSelector(
                state = state,
                onSelected = onSampleRateChanged,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        val rxCanStop = state.rxActive || state.rxBusy ||
            state.connectionState == RadioConnectionState.RECONNECTING
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = if (rxCanStop) onStopRx else onStartRx,
                enabled = !state.txActive && !state.rxStatus.startsWith("Stopping RX") &&
                    (rxCanStop || state.rxAvailable),
                modifier = Modifier.weight(1f),
            ) { Text(if (rxCanStop) "Stop RX" else "Start RX") }
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f),
            ) { Text("Mode: ${state.mode}") }
        }
        Text(state.rxStatus, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        state.appliedSampleRateHz?.let {
            Text(
                "Radio applied ${formatHz(it)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(state.txStatus, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))

        if (state.txRangesUnreported) {
            HorizontalDivider(Modifier.padding(vertical = 20.dp))
            Text("Advanced transmit safety", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Allow TX with unknown hardware limits")
                    Text(
                        "Saved only for this server and radio.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.allowUnknownTxRange,
                    onCheckedChange = { enabled ->
                        if (enabled) showUnknownRangeWarning = true
                        else onAllowUnknownTxRange(false)
                    },
                    enabled = !state.txActive && !state.txBusy,
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 20.dp))
        Text("SoapyRemote connection", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = state.host,
                onValueChange = onHostChanged,
                modifier = Modifier.weight(2f),
                label = { Text("LAN server") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = onPortChanged,
                modifier = Modifier.weight(1f),
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        }
        Button(
            onClick = onDiscover,
            enabled = !state.discovering && !state.inspecting && !state.rxActive && !state.txActive,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) { Text(if (state.discovering) "Discovering…" else "Discover devices") }
        Text(
            state.connectionStatus,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        state.devices.forEach { device ->
            OutlinedButton(
                onClick = { onInspect(device) },
                enabled = !state.inspecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(device.label) }
        }
        if (state.deviceDetails.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = SpectrumBackground),
            ) {
                Text(
                    state.deviceDetails,
                    modifier = Modifier.padding(14.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            "ChrissySDR $version",
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }

    if (showUnknownRangeWarning) {
        AlertDialog(
            onDismissRequest = { showUnknownRangeWarning = false },
            title = { Text("Unknown transmit limits") },
            text = {
                Text(
                    "This radio did not report its transmit frequency ranges. ChrissySDR cannot " +
                        "verify that the selected frequency is supported. Enable transmission only " +
                        "if you have independently verified the radio, frequency, and test setup.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showUnknownRangeWarning = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnknownRangeWarning = false
                        onAllowUnknownTxRange(true)
                    },
                ) { Text("I understand — enable") }
            },
        )
    }
}

@Composable
private fun SampleRateSelector(
    state: RadioUiState,
    onSelected: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = state.sampleRateOptions.isNotEmpty() && !state.rxActive,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("Sample rate", fontSize = 10.sp)
                Text(
                    if (state.sampleRateAutomatic) {
                        "Auto • ${state.sampleRateHz?.let(::formatHz) ?: "unavailable"}"
                    } else {
                        state.sampleRateHz?.let(::formatHz) ?: "unavailable"
                    },
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        "Automatic • " +
                            (state.automaticSampleRateHz?.let(::formatHz) ?: "unavailable"),
                    )
                },
                onClick = {
                    expanded = false
                    onSelected(null)
                },
            )
            state.sampleRateOptions.forEach { rate ->
                DropdownMenuItem(
                    text = { Text(formatHz(rate)) },
                    onClick = {
                        expanded = false
                        onSelected(rate)
                    },
                )
            }
        }
    }
}

@Composable
private fun ChrissySdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SpectrumTrace,
            secondary = FrequencyMarker,
            background = RadioBackground,
            surface = RadioPanel,
            onBackground = Color(0xFFE4F1EF),
            onSurface = Color(0xFFE4F1EF),
        ),
        content = content,
    )
}

private fun displayFrequency(value: Double?): String {
    if (value == null) return "—"
    return if (value >= 1_000_000) "%.6f MHz".format(value / 1_000_000)
    else formatHz(value)
}

private val RadioBackground = Color(0xFF071315)
private val RadioPanel = Color(0xFF102326)
private val SpectrumBackground = Color(0xFF061012)
private val SpectrumTrace = Color(0xFF38D6C7)
private val FrequencyMarker = Color(0xFFFFC857)
private val TxRed = Color(0xFFD83A3A)
