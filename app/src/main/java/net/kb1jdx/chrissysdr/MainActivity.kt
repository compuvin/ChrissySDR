package com.kb1jdx.chrissysdr

import android.Manifest
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
                var microphoneRequestedForTx by remember { mutableStateOf(false) }
                val microphonePermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (!granted && microphoneRequestedForTx) {
                        radio.reportTxAttemptError("Microphone access denied; AM transmit cannot start")
                    }
                    microphoneRequestedForTx = false
                }
                var pendingProfileId by remember { mutableStateOf<String?>(null) }
                val localNetworkPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        pendingProfileId?.let(radio::loadRadioProfile) ?: radio.discover()
                    }
                    else radio.setPermissionMessage("Local-network access was denied")
                    pendingProfileId = null
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
                    onStepFrequency = radio::stepFrequency,
                    onBandwidthChanged = radio::setBandwidth,
                    onModeChanged = radio::setMode,
                    onNfmAudioCutoffChanged = radio::setNfmAudioCutoff,
                    onNfmDeemphasisChanged = radio::setNfmDeemphasis,
                    onSampleRateChanged = radio::selectSampleRate,
                    onSpectrumAveragingChanged = radio::setSpectrumAveraging,
                    onSpectrumFloorChanged = radio::setSpectrumFloorDb,
                    onSpectrumRangeChanged = radio::setSpectrumRangeDb,
                    onSpectrumSpanChanged = radio::setSpectrumSpanHz,
                    onTuningStepChanged = radio::setTuningStepHz,
                    onRxGainChanged = radio::setRxGain,
                    onRxHardwareAgcChanged = radio::setRxHardwareAgc,
                    onRxAntennaChanged = radio::setRxAntenna,
                    onSpectrumTune = radio::tuneSpectrumTo,
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
                    onProfileNameChanged = radio::setProfileName,
                    onSaveProfile = radio::saveRadioProfile,
                    onLoadProfile = { id ->
                        if (Build.VERSION.SDK_INT >= 37 &&
                            checkSelfPermission(LOCAL_NETWORK_PERMISSION) !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingProfileId = id
                            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
                        } else radio.loadRadioProfile(id)
                    },
                    onStartRx = radio::startReceiver,
                    onStopRx = radio::stopReceiver,
                    onTxPressed = {
                        if (state.txActive) {
                            radio.stopTransmitter()
                            true
                        } else {
                            val error = radio.validateTransmit()
                            if (error != null) {
                                radio.reportTxAttemptError(error)
                                false
                            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                microphoneRequestedForTx = true
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
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
    onStepFrequency: (Int) -> Unit,
    onBandwidthChanged: (String) -> Unit,
    onModeChanged: (String) -> Unit,
    onNfmAudioCutoffChanged: (Double) -> Unit,
    onNfmDeemphasisChanged: (Int) -> Unit,
    onSampleRateChanged: (Double?) -> Unit,
    onSpectrumAveragingChanged: (Float) -> Unit,
    onSpectrumFloorChanged: (Int) -> Unit,
    onSpectrumRangeChanged: (Int) -> Unit,
    onSpectrumSpanChanged: (Double?) -> Unit,
    onTuningStepChanged: (Double) -> Unit,
    onRxGainChanged: (String, Double) -> Unit,
    onRxHardwareAgcChanged: (Boolean) -> Unit,
    onRxAntennaChanged: (String) -> Unit,
    onSpectrumTune: (Double) -> Unit,
    onAllowUnknownTxRange: (Boolean) -> Unit,
    onDiscover: () -> Unit,
    onInspect: (RadioDeviceChoice) -> Unit,
    onProfileNameChanged: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onLoadProfile: (String) -> Unit,
    onStartRx: () -> Unit,
    onStopRx: () -> Unit,
    onTxPressed: () -> Boolean,
    onConfirmTx: () -> Unit,
) {
    var activeSheet by rememberSaveable { mutableStateOf<RadioSheet?>(null) }
    var quickConnectExpanded by remember { mutableStateOf(false) }
    var showTransmitConfirmation by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = RadioBackground,
        topBar = {
            RadioHeader(
                state = state,
                quickConnectExpanded = quickConnectExpanded,
                onQuickConnect = {
                    if (state.profiles.isEmpty()) activeSheet = RadioSheet.SETTINGS
                    else quickConnectExpanded = true
                },
                onDismissQuickConnect = { quickConnectExpanded = false },
                onLoadProfile = { id ->
                    quickConnectExpanded = false
                    onLoadProfile(id)
                },
                onSettings = { activeSheet = RadioSheet.SETTINGS },
            )
        },
        bottomBar = {
            OperatingBar(
                frequency = state.frequency.toDoubleOrNull(),
                mode = state.mode,
                transmitting = state.txActive,
                txBusy = state.txBusy,
                onOpenControls = { activeSheet = RadioSheet.OPERATING },
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
            SpectrumDisplay(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = state,
                onTuneFrequency = onSpectrumTune,
                onSpanChanged = onSpectrumSpanChanged,
            )
            Spacer(Modifier.height(12.dp))
            StatusPanel(state)
        }
    }

    if (activeSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            containerColor = RadioPanel,
        ) {
            when (activeSheet) {
                RadioSheet.OPERATING -> OperatingControlsSheet(
                    state = state,
                    onFrequencyChanged = onFrequencyChanged,
                    onStepFrequency = onStepFrequency,
                    onBandwidthChanged = onBandwidthChanged,
                    onModeChanged = onModeChanged,
                    onNfmAudioCutoffChanged = onNfmAudioCutoffChanged,
                    onNfmDeemphasisChanged = onNfmDeemphasisChanged,
                    onTuningStepChanged = onTuningStepChanged,
                    onRxGainChanged = onRxGainChanged,
                    onRxHardwareAgcChanged = onRxHardwareAgcChanged,
                    onStartRx = onStartRx,
                    onStopRx = onStopRx,
                )
                RadioSheet.SETTINGS -> SettingsSheet(
                    state = state,
                    version = version,
                    onHostChanged = onHostChanged,
                    onPortChanged = onPortChanged,
                    onSampleRateChanged = onSampleRateChanged,
                    onRxAntennaChanged = onRxAntennaChanged,
                    onSpectrumAveragingChanged = onSpectrumAveragingChanged,
                    onSpectrumFloorChanged = onSpectrumFloorChanged,
                    onSpectrumRangeChanged = onSpectrumRangeChanged,
                    onSpectrumSpanChanged = onSpectrumSpanChanged,
                    onAllowUnknownTxRange = onAllowUnknownTxRange,
                    onDiscover = onDiscover,
                    onInspect = onInspect,
                    onProfileNameChanged = onProfileNameChanged,
                    onSaveProfile = onSaveProfile,
                    onLoadProfile = { id ->
                        onLoadProfile(id)
                        activeSheet = null
                    },
                )
                null -> Unit
            }
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

private enum class RadioSheet { OPERATING, SETTINGS }

@Composable
private fun RadioHeader(
    state: RadioUiState,
    quickConnectExpanded: Boolean,
    onQuickConnect: () -> Unit,
    onDismissQuickConnect: () -> Unit,
    onLoadProfile: (String) -> Unit,
    onSettings: () -> Unit,
) {
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
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onQuickConnect)) {
                    Text("ChrissySDR", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        headerRadioLabel(state),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                DropdownMenu(
                    expanded = quickConnectExpanded,
                    onDismissRequest = onDismissQuickConnect,
                ) {
                    state.profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(profile.name)
                                    Text("${profile.host}:${profile.port} • ${profile.deviceLabel}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { onLoadProfile(profile.id) },
                            enabled = !state.loadingProfile && !state.savingProfile &&
                                !state.discovering && !state.inspecting &&
                                !state.txActive && !state.txBusy,
                        )
                    }
                }
            }
            TextButton(onClick = onSettings) { Text("SETTINGS") }
        }
    }
}

internal fun headerRadioLabel(state: RadioUiState): String = when {
    state.loadingProfile -> state.profileStatus
    state.connectionState == RadioConnectionState.CONNECTING -> state.connectionStatus
    state.connectionState == RadioConnectionState.RECONNECTING -> state.connectionStatus
    state.rxActive && state.activeProfileId != null -> state.profileName
    state.rxActive && state.selectedDeviceLabel.isNotBlank() -> state.selectedDeviceLabel
    state.connectionState == RadioConnectionState.CONNECTED &&
        state.selectedDeviceLabel.isNotBlank() -> "${state.selectedDeviceLabel} • RX stopped"
    state.connectionState == RadioConnectionState.FAILED -> state.connectionStatus
    else -> "No radio connected — tap to connect"
}

@Composable
private fun SpectrumDisplay(
    modifier: Modifier,
    state: RadioUiState,
    onTuneFrequency: (Double) -> Unit,
    onSpanChanged: (Double?) -> Unit,
) {
    val frame = state.spectrum
    val spanHz = frame?.let { (state.spectrumSpanHz ?: it.sampleRateHz).coerceAtMost(it.sampleRateHz) }
    val centerFrequency = frame?.centerFrequencyHz ?: state.frequency.toDoubleOrNull()
    var dragPreviewHz by remember { mutableStateOf<Double?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val latestState by rememberUpdatedState(state)
    val latestTune by rememberUpdatedState(onTuneFrequency)
    val latestSpanChange by rememberUpdatedState(onSpanChanged)
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SpectrumBackground),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(
                Modifier.fillMaxSize().padding(12.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val initialFrame = latestState.spectrum
                            if (initialFrame == null || latestState.rxBusy) return@awaitEachGesture
                            val initialSpan = (latestState.spectrumSpanHz ?: initialFrame.sampleRateHz)
                                .coerceAtMost(initialFrame.sampleRateHz)
                            val center = initialFrame.centerFrequencyHz
                            val step = latestState.tuningStepHz
                            val touchSlop = viewConfiguration.touchSlop
                            var dragging = false
                            var pinching = false
                            var pinchDistance = 0f
                            var pinchSpan = initialSpan
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    if (!pinching) {
                                        pinching = true
                                        pinchSpan = latestState.spectrumSpanHz ?: initialFrame.sampleRateHz
                                        dragPreviewHz = null
                                        dragOffsetPx = 0f
                                    }
                                    val distance = (pressed[0].position - pressed[1].position).getDistance()
                                    if (pinchDistance == 0f) pinchDistance = distance
                                    val maximum = minOf(initialFrame.sampleRateHz, 48_000.0)
                                    val zoomed = SpectrumTuning.zoomSpan(
                                        pinchSpan, distance.toDouble() / pinchDistance, maximum,
                                    )
                                    zoomed?.let(latestSpanChange)
                                    pressed.forEach { it.consume() }
                                } else if (pressed.isEmpty()) {
                                    if (!pinching) {
                                        if (dragging) dragPreviewHz?.let(latestTune)
                                        else SpectrumTuning.tap(
                                            center, initialSpan, down.position.x,
                                            size.width.toFloat(), step,
                                        )?.let(latestTune)
                                    }
                                    dragPreviewHz = null
                                    dragOffsetPx = 0f
                                    break
                                } else if (!pinching) {
                                    val position = pressed[0].position
                                    if (!dragging && (position - down.position).getDistance() > touchSlop) {
                                        dragging = true
                                    }
                                    if (dragging) {
                                        val distance = position.x - down.position.x
                                        dragOffsetPx = distance.coerceIn(
                                            -size.width.toFloat(), size.width.toFloat(),
                                        )
                                        dragPreviewHz = SpectrumTuning.drag(
                                            center, initialSpan, distance, size.width.toFloat(), step,
                                        )
                                        pressed[0].consume()
                                    }
                                }
                            }
                        }
                    },
            ) {
                val gridColor = Color(0x2638D6C7)
                repeat(9) { index ->
                    val x = size.width * index / 8f
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                }
                repeat(7) { index ->
                    val y = size.height * index / 6f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                }
                if (frame != null && spanHz != null && spanHz > 0.0) {
                    val passband = state.bandwidth.toDoubleOrNull() ?: 0.0
                    val lower = when (state.mode) {
                        "USB" -> 0.0
                        "LSB" -> -passband
                        else -> -passband / 2.0
                    }
                    val upper = when (state.mode) {
                        "USB" -> passband
                        "LSB" -> 0.0
                        else -> passband / 2.0
                    }
                    val left = (size.width * (0.5 + lower / spanHz)).toFloat()
                        .coerceIn(0f, size.width)
                    val right = (size.width * (0.5 + upper / spanHz)).toFloat()
                        .coerceIn(0f, size.width)
                    if (right > left) drawRect(
                        FrequencyMarker.copy(alpha = 0.12f),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, size.height),
                    )
                    val bins = frame.binsDbfs
                    val path = Path()
                    val points = size.width.toInt().coerceIn(2, bins.size)
                    var pathStarted = false
                    val visibleShift = dragOffsetPx / size.width
                    for (point in 0 until points) {
                        val fraction = point.toDouble() / (points - 1)
                        val frequencyOffset = (fraction - 0.5 - visibleShift) * spanHz
                        if (frequencyOffset !in -frame.sampleRateHz / 2..frame.sampleRateHz / 2) {
                            pathStarted = false
                            continue
                        }
                        val bin = ((frequencyOffset / frame.sampleRateHz + 0.5) * bins.size)
                            .toInt().coerceIn(0, bins.lastIndex)
                        val nextOffset = ((point + 1.0) / (points - 1) - 0.5 - visibleShift) * spanHz
                        val nextBin = ((nextOffset / frame.sampleRateHz + 0.5) * bins.size)
                            .toInt().coerceIn(bin, bins.lastIndex)
                        var peak = bins[bin]
                        for (candidate in bin + 1..nextBin) peak = maxOf(peak, bins[candidate])
                        val level = ((peak - state.spectrumFloorDb) /
                            state.spectrumRangeDb).coerceIn(0f, 1f)
                        val x = size.width * fraction.toFloat()
                        val y = size.height * (1f - level)
                        if (!pathStarted) path.moveTo(x, y) else path.lineTo(x, y)
                        pathStarted = true
                    }
                    drawPath(path, SpectrumTrace, style = Stroke(2f, cap = StrokeCap.Round))
                }
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
                Text("LIVE SPECTRUM", color = SpectrumTrace, fontWeight = FontWeight.Bold)
                Text(
                    if (frame != null) "${formatHz(spanHz!!)} span • ${frame.binsDbfs.size} bins"
                    else if (state.rxActive) "Waiting for IQ samples…" else "Start RX to see signals",
                    color = Color(0xFF91A8A5),
                    fontSize = 12.sp,
                )
            }
            dragPreviewHz?.let { preview ->
                Text(
                    "Tune ${displayFrequency(preview)}",
                    modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = FrequencyMarker,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val halfSpan = (spanHz ?: 0.0) / 2.0
                val displayedCenter = dragPreviewHz ?: centerFrequency
                Text(displayFrequency(displayedCenter?.minus(halfSpan)), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, color = Color.White)
                Text(displayFrequency(displayedCenter?.plus(halfSpan)), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, color = Color.White)
            }
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
            if (!state.txActive && state.txAttemptError != null) {
                Text(state.txAttemptError, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OperatingBar(
    frequency: Double?,
    mode: String,
    transmitting: Boolean,
    txBusy: Boolean,
    onOpenControls: () -> Unit,
    onTx: () -> Unit,
) {
    Surface(color = RadioPanel, tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onOpenControls),
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
                enabled = !txBusy,
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
                modifier = Modifier.weight(1f).clickable(onClick = onOpenControls),
                horizontalAlignment = Alignment.End,
            ) {
                Text("MODE", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(mode, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SpectrumTrace)
                Text("Tap for controls", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OperatingControlsSheet(
    state: RadioUiState,
    onFrequencyChanged: (String) -> Unit,
    onStepFrequency: (Int) -> Unit,
    onBandwidthChanged: (String) -> Unit,
    onModeChanged: (String) -> Unit,
    onNfmAudioCutoffChanged: (Double) -> Unit,
    onNfmDeemphasisChanged: (Int) -> Unit,
    onTuningStepChanged: (Double) -> Unit,
    onRxGainChanged: (String, Double) -> Unit,
    onRxHardwareAgcChanged: (Boolean) -> Unit,
    onStartRx: () -> Unit,
    onStopRx: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text("Operating controls", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Tune and adjust the current receiver.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = state.frequency,
                onValueChange = onFrequencyChanged,
                modifier = Modifier.weight(1f),
                label = { Text("Frequency (Hz)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !state.txActive && !state.txBusy,
            )
            val canStep = !state.txActive && !state.txBusy && !state.rxBusy
            OutlinedButton(
                onClick = { onStepFrequency(-1) },
                enabled = canStep,
                modifier = Modifier.width(44.dp).semantics {
                    contentDescription = "Decrease frequency by ${formatHz(state.tuningStepHz)}"
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("▼") }
            OutlinedButton(
                onClick = { onStepFrequency(1) },
                enabled = canStep,
                modifier = Modifier.width(44.dp).semantics {
                    contentDescription = "Increase frequency by ${formatHz(state.tuningStepHz)}"
                },
                contentPadding = PaddingValues(0.dp),
            ) { Text("▲") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.bandwidth,
            onValueChange = onBandwidthChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(when (state.mode) {
                "AM" -> "AM RF width (Hz)"
                "NFM" -> "NFM RF width (Hz)"
                else -> "SSB passband (Hz)"
            }) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !state.txActive && !state.txBusy,
        )
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
            var modeMenuExpanded by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = { modeMenuExpanded = true },
                    enabled = !state.rxBusy && !state.txActive && !state.txBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Mode: ${state.mode}") }
                DropdownMenu(
                    expanded = modeMenuExpanded,
                    onDismissRequest = { modeMenuExpanded = false },
                ) {
                    listOf("AM", "NFM", "USB", "LSB").forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode) },
                            onClick = { onModeChanged(mode); modeMenuExpanded = false },
                        )
                    }
                }
            }
        }
        if (state.mode == "NFM") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var audioExpanded by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { audioExpanded = true },
                        enabled = !state.rxBusy && !state.txActive && !state.txBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Audio: ${formatHz(state.nfmAudioCutoffHz)}") }
                    DropdownMenu(audioExpanded, onDismissRequest = { audioExpanded = false }) {
                        listOf(2_500.0, 3_000.0, 4_000.0).forEach { cutoff ->
                            DropdownMenuItem(
                                text = { Text(formatHz(cutoff)) },
                                onClick = { onNfmAudioCutoffChanged(cutoff); audioExpanded = false },
                            )
                        }
                    }
                }
                var deemphasisExpanded by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { deemphasisExpanded = true },
                        enabled = !state.rxBusy && !state.txActive && !state.txBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Deemphasis: ${if (state.nfmDeemphasisUs == 0) "Off" else "${state.nfmDeemphasisUs} µs"}") }
                    DropdownMenu(deemphasisExpanded, onDismissRequest = { deemphasisExpanded = false }) {
                        listOf(0, 50, 75).forEach { microseconds ->
                            DropdownMenuItem(
                                text = { Text(if (microseconds == 0) "Off" else "$microseconds µs") },
                                onClick = { onNfmDeemphasisChanged(microseconds); deemphasisExpanded = false },
                            )
                        }
                    }
                }
            }
        }
        Text(state.rxStatus, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        state.appliedSampleRateHz?.let {
            Text("Radio applied ${formatHz(it)}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        TuningStepSelector(state.tuningStepHz, onTuningStepChanged)
        if (state.rxHardwareAgcSupported) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Radio AGC", modifier = Modifier.weight(1f))
                Switch(
                    checked = state.rxHardwareAgc == true,
                    onCheckedChange = onRxHardwareAgcChanged,
                    enabled = state.rxHardwareAgc != null && !state.rxBusy && !state.txActive && !state.txBusy,
                )
            }
            if (state.rxHardwareAgc == null) Text("Radio AGC state unavailable", fontSize = 12.sp)
        }
        state.rxGainRanges.forEach { (name, range) ->
            val current = state.rxGainValues[name]
            if (current != null && current.isFinite() && range.minimum.isFinite() &&
                range.maximum.isFinite() && range.maximum > range.minimum &&
                range.minimum.toFloat().isFinite() && range.maximum.toFloat().isFinite()
            ) {
                var draft by remember(name, current) { mutableStateOf(current.toFloat()) }
                Text("RX $name gain: %.1f dB".format(draft), fontSize = 13.sp)
                Slider(
                    value = draft.coerceIn(range.minimum.toFloat(), range.maximum.toFloat()),
                    onValueChange = { draft = it },
                    onValueChangeFinished = { onRxGainChanged(name, draft.toDouble()) },
                    valueRange = range.minimum.toFloat()..range.maximum.toFloat(),
                    enabled = (!state.rxHardwareAgcSupported || state.rxHardwareAgc == false) &&
                        !state.rxBusy && !state.txActive && !state.txBusy,
                )
            } else {
                Text("RX $name gain unavailable: current value or range not reported",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.rxHardwareAgc == true && state.rxGainRanges.isNotEmpty()) {
            Text("Switch off Radio AGC to adjust gain", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Tap the spectrum to select a signal; drag and release to tune.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TuningStepSelector(stepHz: Double, onSelected: (Double) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Tuning step: ${formatHz(stepHz)}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            listOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0).forEach { step ->
                DropdownMenuItem(
                    text = { Text(formatHz(step)) },
                    onClick = { onSelected(step); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SpectrumSpanSelector(state: RadioUiState, onSelected: (Double?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val availableRate = minOf(state.appliedSampleRateHz ?: state.sampleRateHz ?: 48_000.0, 48_000.0)
    val spans = listOf(null, 3_000.0, 6_000.0, 12_000.0, 24_000.0, 48_000.0)
        .filter { it == null || it <= availableRate }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("Spectrum span: ${state.spectrumSpanHz?.let(::formatHz) ?: "auto"}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            spans.forEach { span ->
                DropdownMenuItem(
                    text = { Text(span?.let(::formatHz) ?: "Auto") },
                    onClick = { onSelected(span); expanded = false },
                )
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
    onSampleRateChanged: (Double?) -> Unit,
    onRxAntennaChanged: (String) -> Unit,
    onSpectrumAveragingChanged: (Float) -> Unit,
    onSpectrumFloorChanged: (Int) -> Unit,
    onSpectrumRangeChanged: (Int) -> Unit,
    onSpectrumSpanChanged: (Double?) -> Unit,
    onAllowUnknownTxRange: (Boolean) -> Unit,
    onDiscover: () -> Unit,
    onInspect: (RadioDeviceChoice) -> Unit,
    onProfileNameChanged: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onLoadProfile: (String) -> Unit,
) {
    var showUnknownRangeWarning by remember { mutableStateOf(false) }
    var showAdditionalRadioInfo by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val diagnostics = diagnosticsText(state, version)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Configure radios, profiles, and the app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        ConnectionSetupSection(
            state = state,
            onHostChanged = onHostChanged,
            onPortChanged = onPortChanged,
            onDiscover = onDiscover,
            onInspect = onInspect,
            onProfileNameChanged = onProfileNameChanged,
            onSaveProfile = onSaveProfile,
            onShowAdditionalRadioInfo = { showAdditionalRadioInfo = true },
        )
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Saved radios", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (state.profiles.isEmpty()) {
            Text("No radio profiles saved yet.", fontSize = 12.sp)
        }
        state.profiles.forEach { profile ->
            Button(
                onClick = { onLoadProfile(profile.id) },
                enabled = !state.savingProfile && !state.loadingProfile && !state.discovering && !state.inspecting &&
                    !state.txActive && !state.txBusy,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text("${profile.name} • ${profile.host}:${profile.port}") }
        }
        if (state.loadingProfile || state.profileStatus.isNotBlank()) {
            Text(state.profileStatus, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Advanced sample rate", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        SampleRateSelector(
            state = state,
            onSelected = onSampleRateChanged,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (state.rxAntennas.size > 1) {
            var antennaExpanded by remember { mutableStateOf(false) }
            Text("RX antenna", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp))
            Box {
                OutlinedButton(
                    onClick = { antennaExpanded = true },
                    enabled = !state.rxBusy && !state.txActive && !state.txBusy,
                ) { Text(state.rxAntenna ?: "Choose antenna") }
                DropdownMenu(antennaExpanded, onDismissRequest = { antennaExpanded = false }) {
                    state.rxAntennas.forEach { antenna ->
                        DropdownMenuItem(
                            text = { Text(antenna) },
                            onClick = { onRxAntennaChanged(antenna); antennaExpanded = false },
                        )
                    }
                }
            }
            if (state.rxBusy || state.txActive || state.txBusy) {
                Text("Antenna selection is unavailable while RX is switching or TX is active",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Spectrum", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Display controls do not change the radio stream.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SpectrumSpanSelector(state, onSpectrumSpanChanged)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var averagingExpanded by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { averagingExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Avg: ${when (state.spectrumAveraging) {
                        0.15f -> "slow"
                        0.7f -> "fast"
                        1f -> "off"
                        else -> "medium"
                    }}")
                }
                DropdownMenu(averagingExpanded, onDismissRequest = { averagingExpanded = false }) {
                    listOf("Slow" to 0.15f, "Medium" to 0.35f, "Fast" to 0.7f, "Off" to 1f)
                        .forEach { (label, weight) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { onSpectrumAveragingChanged(weight); averagingExpanded = false },
                            )
                        }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var floorExpanded by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { floorExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Floor: ${state.spectrumFloorDb} dB")
                }
                DropdownMenu(floorExpanded, onDismissRequest = { floorExpanded = false }) {
                    listOf(-140, -120, -100, -80, -60).forEach { floor ->
                        DropdownMenuItem(
                            text = { Text("$floor dBFS") },
                            onClick = { onSpectrumFloorChanged(floor); floorExpanded = false },
                        )
                    }
                }
            }
            var rangeExpanded by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { rangeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Range: ${state.spectrumRangeDb} dB")
                }
                DropdownMenu(rangeExpanded, onDismissRequest = { rangeExpanded = false }) {
                    listOf(40, 60, 80, 100, 120).forEach { range ->
                        DropdownMenuItem(
                            text = { Text("$range dB") },
                            onClick = { onSpectrumRangeChanged(range); rangeExpanded = false },
                        )
                    }
                }
            }
        }
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

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Diagnostics", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Connection, radio, stream, and recent error information.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            onClick = { showDiagnostics = true },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text("View diagnostics") }

        Text(
            "ChrissySDR $version",
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }

    if (showAdditionalRadioInfo) {
        AlertDialog(
            onDismissRequest = { showAdditionalRadioInfo = false },
            title = { Text("Additional Radio Info") },
            text = {
                Text(
                    state.additionalDeviceDetails,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showAdditionalRadioInfo = false }) { Text("Close") }
            },
        )
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Diagnostics") },
            text = {
                Text(
                    diagnostics,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("ChrissySDR diagnostics", diagnostics))
                    }) { Text("Copy") }
                    TextButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "ChrissySDR diagnostics")
                            putExtra(Intent.EXTRA_TEXT, diagnostics)
                        }
                        context.startActivity(Intent.createChooser(share, "Share diagnostics"))
                    }) { Text("Share") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text("Close") }
            },
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
private fun ConnectionSetupSection(
    state: RadioUiState,
    onHostChanged: (String) -> Unit,
    onPortChanged: (String) -> Unit,
    onDiscover: () -> Unit,
    onInspect: (RadioDeviceChoice) -> Unit,
    onProfileNameChanged: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onShowAdditionalRadioInfo: () -> Unit,
) {
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
    if (state.rxActive) Text("Stop RX before discovering another radio", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (state.selectedDeviceLabel.isNotBlank()) {
        OutlinedTextField(
            value = state.profileName,
            onValueChange = onProfileNameChanged,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("Radio profile name") },
            singleLine = true,
        )
        Button(
            onClick = onSaveProfile,
            enabled = !state.savingProfile && !state.loadingProfile && !state.discovering && !state.inspecting &&
                state.profileName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Text(if (state.activeProfileId == null) "Save radio profile" else "Update radio profile")
        }
    }
    if (state.deviceDetails.isNotBlank()) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = SpectrumBackground),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(state.deviceDetails, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                if (state.connectionState == RadioConnectionState.CONNECTED &&
                    state.additionalDeviceDetails.isNotBlank()
                ) {
                    TextButton(onClick = onShowAdditionalRadioInfo) {
                        Text("Additional Radio Info")
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleRateSelector(
    state: RadioUiState,
    onSelected: (Double?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val passband = state.bandwidth.toDoubleOrNull()
    val usableRates = if (passband == null) emptyList() else state.sampleRateOptions.filter {
        SampleRatePolicy.isUsable(it, state.mode, passband)
    }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = usableRates.isNotEmpty() && !state.rxActive && !state.rxBusy &&
                !state.txActive && !state.txBusy,
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
            usableRates.forEach { rate ->
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
    if (state.rxActive) Text("Stop RX to change sample rate", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (state.txActive || state.txBusy) Text("Sample rate is unavailable during TX", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (state.sampleRateOptions.isNotEmpty() && usableRates.isEmpty())
        Text("No advertised sample rate can contain this passband", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
