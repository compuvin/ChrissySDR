package com.kb1jdx.chrissysdr.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.core.content.ContextCompat
import com.kb1jdx.chrissysdr.MainActivity
import com.kb1jdx.chrissysdr.R

class RadioService : Service() {
    inner class LocalBinder : Binder() {
        val service: RadioService get() = this@RadioService
    }

    private val binder = LocalBinder()
    private val backend: RadioBackend = SoapyRemoteBackend()
    private val lock = Any()
    private var receiver: RadioReceiver? = null
    private var transmitter: RadioTransmitter? = null
    private var receiverConfig: ReceiverConfig? = null
    private var transmitterConfig: TransmitterConfig? = null
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var rxFocusRequest: AudioFocusRequest
    private var rxFocusRequested = false
    private var rxAudioVolume = 1f
    private var rxUserPaused = false
    @Volatile private var pendingRxOpen: RadioOpenCancellation? = null
    @Volatile private var stateListener: ((Boolean, Boolean) -> Unit)? = null
    @Volatile private var externalStopListener: ((String) -> Unit)? = null
    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                stopReceiverForExternalReason("RX stopped: headphones or Bluetooth audio disconnected")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Active radio",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "ChrissySDR receiver and transmitter status" },
        )
        mediaSession = MediaSession(this, "ChrissySDR").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onStop() = stopRadioService()
                override fun onPause() = setRxPaused(true)
                override fun onPlay() = setRxPaused(false)
            })
        }
        audioManager = getSystemService(AudioManager::class.java)
        rxFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setOnAudioFocusChangeListener(
                AudioManager.OnAudioFocusChangeListener(::onRxAudioFocusChanged),
                Handler(Looper.getMainLooper()),
            )
            .build()
        ContextCompat.registerReceiver(
            this,
            noisyAudioReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRadioService("Radio stopped from notification")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE_RX) setRxPaused(true)
        if (intent?.action == ACTION_RESUME_RX) setRxPaused(false)
        return if (isStreaming()) START_STICKY else START_NOT_STICKY
    }

    fun discover(endpoint: RadioEndpoint): RadioDiscovery = backend.discover(endpoint)

    fun inspect(
        endpoint: RadioEndpoint,
        deviceArguments: Map<String, String>,
    ): RadioDeviceCapabilities = backend.inspect(endpoint, deviceArguments)

    fun setStateListener(listener: ((receiving: Boolean, transmitting: Boolean) -> Unit)?) {
        stateListener = listener
        notifyState()
    }

    fun setExternalStopListener(listener: ((String) -> Unit)?) {
        externalStopListener = listener
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Callbacks still belong to the activity's view model, so no stream should outlive it.
        stopRadioService("Radio stopped: app removed from Recents")
        super.onTaskRemoved(rootIntent)
    }

    fun startReceiver(
        config: ReceiverConfig,
        onStatistics: (ReceiverStatistics) -> Unit,
        onSpectrum: (SpectrumFrame) -> Unit,
        onError: (Throwable) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): ReceiverAppliedSettings = synchronized(lock) {
        check(!isCancelled()) { "RX start cancelled" }
        runCatching { receiver?.close() }
        receiver = null
        receiverConfig = null
        val cancellation = RadioOpenCancellation()
        pendingRxOpen = cancellation
        try {
            check(!isCancelled() && !cancellation.isCancelled()) { "RX start cancelled" }
            val session = backend.openReceiver(config, cancellation)
            receiver = session
            try {
                check(!isCancelled() && !cancellation.isCancelled()) { "RX start cancelled" }
                receiverConfig = config
                promote()
                check(requestRxAudioFocus()) { "Android audio focus is unavailable" }
                rxUserPaused = false
                session.setAudioVolume(rxAudioVolume)
                session.start(
                    onStatistics = onStatistics,
                    onSpectrum = onSpectrum,
                    onError = { error ->
                        val current = synchronized(lock) {
                            if (receiver === session) {
                                receiver = null
                                receiverConfig = null
                                true
                            } else false
                        }
                        if (current) {
                            runCatching { session.close() }
                            abandonRxAudioFocusIfIdle()
                            notifyState()
                            updateForegroundState()
                            onError(error)
                        }
                    },
                )
                check(!isCancelled() && !cancellation.isCancelled()) { "RX start cancelled" }
                if (receiver === session) {
                    notifyState()
                }
                ReceiverAppliedSettings(session.appliedSampleRate, session.appliedHardwareBandwidth)
            } catch (error: Throwable) {
                if (receiver === session) {
                    receiver = null
                    receiverConfig = null
                }
                runCatching { session.close() }
                abandonRxAudioFocusIfIdle()
                throw error
            }
        } catch (error: Throwable) {
            abandonRxAudioFocusIfIdle()
            notifyState()
            updateForegroundState()
            throw error
        } finally {
            if (pendingRxOpen === cancellation) pendingRxOpen = null
            cancellation.release()
        }
    }

    fun cancelPendingReceiver() {
        pendingRxOpen?.cancel()
    }

    fun stopReceiver() {
        cancelPendingReceiver()
        val session = synchronized(lock) {
            val current = receiver
            receiver = null
            receiverConfig = null
            rxUserPaused = false
            current
        }
        runCatching { session?.close() }
        abandonRxAudioFocusIfIdle()
        notifyState()
        updateForegroundState()
    }

    fun startTransmitter(
        config: TransmitterConfig,
        onStatistics: (TransmitterStatistics) -> Unit,
        onStopped: () -> Unit,
        onError: (Throwable) -> Unit,
    ): Double = synchronized(lock) {
        runCatching { receiver?.close() }
        receiver = null
        receiverConfig = null
        abandonRxAudioFocusIfIdle()
        runCatching { transmitter?.close() }
        transmitter = null
        transmitterConfig = null
        val session = try {
            backend.openTransmitter(config)
        } catch (error: Throwable) {
            notifyState()
            updateForegroundState()
            throw error
        }
        transmitter = session
        try {
            session.start(
                onStatistics = onStatistics,
                onStopped = onStopped,
                onError = { error ->
                    val current = synchronized(lock) {
                        if (transmitter === session) {
                            transmitter = null
                            transmitterConfig = null
                            true
                        } else false
                    }
                    if (current) {
                        runCatching { session.close() }
                        notifyState()
                        updateForegroundState()
                        onError(error)
                    }
                },
            )
            if (transmitter === session) {
                transmitterConfig = config
                promote()
                notifyState()
            }
            session.appliedSampleRate
        } catch (error: Throwable) {
            if (transmitter === session) {
                transmitter = null
                transmitterConfig = null
            }
            runCatching { session.close() }
            notifyState()
            updateForegroundState()
            throw error
        }
    }

    fun stopTransmitter() {
        val session = synchronized(lock) {
            val current = transmitter
            transmitter = null
            transmitterConfig = null
            current
        }
        runCatching { session?.close() }
        notifyState()
        updateForegroundState()
    }

    fun closeAll() {
        cancelPendingReceiver()
        val sessions = synchronized(lock) {
            val current = receiver to transmitter
            receiver = null
            transmitter = null
            receiverConfig = null
            transmitterConfig = null
            current
        }
        runCatching { sessions.first?.close() }
        runCatching { sessions.second?.close() }
        abandonRxAudioFocusIfIdle()
        notifyState()
    }

    override fun onDestroy() {
        closeAll()
        unregisterReceiver(noisyAudioReceiver)
        mediaSession.release()
        super.onDestroy()
    }

    private fun stopRadioService(reason: String = "Radio stopped") {
        externalStopListener?.invoke(reason)
        closeAll()
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopReceiverForExternalReason(reason: String) {
        val session = synchronized(lock) {
            receiver?.also { runCatching { it.setAudioVolume(0f) } }
        } ?: return
        externalStopListener?.invoke(reason)
        Thread({
            val current = synchronized(lock) {
                if (receiver === session) {
                    receiver = null
                    receiverConfig = null
                    true
                } else false
            }
            if (current) {
                runCatching { session.close() }
                abandonRxAudioFocusIfIdle()
                notifyState()
                updateForegroundState()
            }
        }, "ChrissySDR-route-change").start()
    }

    private fun requestRxAudioFocus(): Boolean {
        if (rxFocusRequested) return true
        if (audioManager.requestAudioFocus(rxFocusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return false
        }
        rxFocusRequested = true
        rxAudioVolume = 1f
        return true
    }

    private fun abandonRxAudioFocusIfIdle() {
        synchronized(lock) {
            if (receiver == null && rxFocusRequested) {
                rxFocusRequested = false
                rxAudioVolume = 1f
                audioManager.abandonAudioFocusRequest(rxFocusRequest)
            }
        }
    }

    private fun onRxAudioFocusChanged(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            val session = synchronized(lock) {
                if (!rxFocusRequested) return
                rxFocusRequested = false
                rxAudioVolume = 0f
                val active = receiver
                runCatching { active?.setAudioVolume(0f) }
                audioManager.abandonAudioFocusRequest(rxFocusRequest)
                active
            }
            if (session != null) externalStopListener?.invoke("RX stopped: audio focus lost")
            if (session != null) Thread({
                val current = synchronized(lock) {
                    if (receiver === session) {
                        receiver = null
                        receiverConfig = null
                        true
                    } else false
                }
                if (current) {
                    runCatching { session.close() }
                    notifyState()
                    updateForegroundState()
                }
            }, "ChrissySDR-focus-loss").start()
            return
        }
        val volume = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> 1f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> 0f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.2f
            else -> return
        }
        val session = synchronized(lock) {
            if (!rxFocusRequested) return
            rxAudioVolume = volume
            receiver
        }
        runCatching { session?.setAudioVolume(if (rxUserPaused) 0f else volume) }
        if (session != null) updateForegroundState()
    }

    private fun setRxPaused(paused: Boolean) {
        val session = synchronized(lock) {
            if (receiver == null) return
            rxUserPaused = paused
            receiver
        }
        runCatching { session?.setAudioVolume(if (paused) 0f else rxAudioVolume) }
        updateForegroundState()
    }

    private fun promote() {
        startService(Intent(this, RadioService::class.java))
        startForeground(NOTIFICATION_ID, notification())
    }

    private fun updateForegroundState() {
        if (!isStreaming()) {
            mediaSession.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification())
        }
    }

    private fun isStreaming(): Boolean = synchronized(lock) {
        receiver != null || transmitter != null
    }

    private fun notifyState() {
        val state = synchronized(lock) { (receiver != null) to (transmitter != null) }
        stateListener?.invoke(state.first, state.second)
    }

    private fun notification(): Notification {
        val (rx, tx, paused) = synchronized(lock) { Triple(receiverConfig, transmitterConfig, rxUserPaused) }
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(rx?.let { formatFrequency(it.frequencyHz) }
                ?: tx?.let { formatFrequency(it.frequencyHz) }
                ?: "ChrissySDR")
            .setContentText(when {
                rx != null -> "${rx.mode.uppercase()} • ${if (paused) "Audio paused; RX active" else "Receiving"}"
                tx != null -> "Transmitting"
                else -> "Radio active"
            })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stop),
                "Stop audio",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, RadioService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build())

        if (rx != null) builder.addAction(Notification.Action.Builder(
            Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
            if (paused) "Resume audio" else "Pause audio",
            PendingIntent.getService(
                this,
                2,
                Intent(this, RadioService::class.java).setAction(if (paused) ACTION_RESUME_RX else ACTION_PAUSE_RX),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build())

        if (rx != null) {
            mediaSession.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, formatFrequency(rx.frequencyHz))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "${rx.mode.uppercase()} • ChrissySDR")
                .build())
            mediaSession.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY)
                .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (paused) 0f else 1f)
                .build())
            mediaSession.isActive = Build.VERSION.SDK_INT < 33
            if (Build.VERSION.SDK_INT < 33) builder.setStyle(Notification.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1))
        } else {
            mediaSession.isActive = false
        }
        return builder.build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "active_radio"
        private const val NOTIFICATION_ID = 1500
        private const val ACTION_STOP = "com.kb1jdx.chrissysdr.STOP_RADIO"
        private const val ACTION_PAUSE_RX = "com.kb1jdx.chrissysdr.PAUSE_RX"
        private const val ACTION_RESUME_RX = "com.kb1jdx.chrissysdr.RESUME_RX"
    }
}

data class ReceiverAppliedSettings(
    val sampleRate: Double,
    val hardwareBandwidth: Double?,
)

private fun formatFrequency(hz: Double): String = if (hz >= 1_000_000) {
    "%.6f MHz".format(hz / 1_000_000)
} else {
    "%.3f kHz".format(hz / 1_000)
}
