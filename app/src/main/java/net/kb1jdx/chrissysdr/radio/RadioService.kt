package com.kb1jdx.chrissysdr.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.IBinder
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
    @Volatile private var pendingRxOpen: RadioOpenCancellation? = null
    @Volatile private var stateListener: ((Boolean, Boolean) -> Unit)? = null

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
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRadioService()
            return START_NOT_STICKY
        }
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
                            notifyState()
                            updateForegroundState()
                            onError(error)
                        }
                    },
                )
                check(!isCancelled() && !cancellation.isCancelled()) { "RX start cancelled" }
                if (receiver === session) {
                    receiverConfig = config
                    promote()
                    notifyState()
                }
                ReceiverAppliedSettings(session.appliedSampleRate, session.appliedHardwareBandwidth)
            } catch (error: Throwable) {
                if (receiver === session) {
                    receiver = null
                    receiverConfig = null
                }
                runCatching { session.close() }
                throw error
            }
        } catch (error: Throwable) {
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
            current
        }
        runCatching { session?.close() }
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
        notifyState()
    }

    override fun onDestroy() {
        closeAll()
        mediaSession.release()
        super.onDestroy()
    }

    private fun stopRadioService() {
        closeAll()
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        val (rx, tx) = synchronized(lock) { receiverConfig to transmitterConfig }
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(rx?.let { formatFrequency(it.frequencyHz) }
                ?: tx?.let { formatFrequency(it.frequencyHz) }
                ?: "ChrissySDR")
            .setContentText(when {
                rx != null -> "${rx.mode.uppercase()} • Receiving"
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
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, RadioService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build())

        if (rx != null) {
            mediaSession.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, formatFrequency(rx.frequencyHz))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "${rx.mode.uppercase()} • ChrissySDR")
                .build())
            mediaSession.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_STOP)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build())
            mediaSession.isActive = true
            builder.setStyle(Notification.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0))
        } else {
            mediaSession.isActive = false
        }
        return builder.build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "active_radio"
        private const val NOTIFICATION_ID = 1500
        private const val ACTION_STOP = "com.kb1jdx.chrissysdr.STOP_RADIO"
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
