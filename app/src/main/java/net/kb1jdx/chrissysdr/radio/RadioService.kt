package com.kb1jdx.chrissysdr.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            closeAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        onError: (Throwable) -> Unit,
    ): Double = synchronized(lock) {
        receiver?.close()
        val session = backend.openReceiver(config)
        try {
            session.start(
                onStatistics = onStatistics,
                onError = { error ->
                    synchronized(lock) {
                        if (receiver === session) receiver = null
                    }
                    updateForegroundState()
                    onError(error)
                },
            )
            receiver = session
            promote("Receiving ${formatFrequency(config.frequencyHz)}")
            notifyState()
            session.appliedSampleRate
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    fun stopReceiver() {
        val session = synchronized(lock) {
            val current = receiver
            receiver = null
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
        receiver?.close()
        receiver = null
        transmitter?.close()
        val session = backend.openTransmitter(config)
        try {
            session.start(
                onStatistics = onStatistics,
                onStopped = onStopped,
                onError = { error ->
                    synchronized(lock) {
                        if (transmitter === session) transmitter = null
                    }
                    updateForegroundState()
                    onError(error)
                },
            )
            transmitter = session
            promote("Transmitting ${formatFrequency(config.frequencyHz)}")
            notifyState()
            session.appliedSampleRate
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    fun stopTransmitter() {
        val session = synchronized(lock) {
            val current = transmitter
            transmitter = null
            current
        }
        runCatching { session?.close() }
        notifyState()
        updateForegroundState()
    }

    fun closeAll() {
        val sessions = synchronized(lock) {
            val current = receiver to transmitter
            receiver = null
            transmitter = null
            current
        }
        runCatching { sessions.first?.close() }
        runCatching { sessions.second?.close() }
        notifyState()
    }

    override fun onDestroy() {
        closeAll()
        super.onDestroy()
    }

    private fun promote(status: String) {
        startService(Intent(this, RadioService::class.java))
        startForeground(NOTIFICATION_ID, notification(status))
    }

    private fun updateForegroundState() {
        val status = synchronized(lock) {
            when {
                transmitter != null -> "Transmitting"
                receiver != null -> "Receiving"
                else -> null
            }
        }
        if (status == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification(status))
        }
    }

    private fun isStreaming(): Boolean = synchronized(lock) {
        receiver != null || transmitter != null
    }

    private fun notifyState() {
        val state = synchronized(lock) { (receiver != null) to (transmitter != null) }
        stateListener?.invoke(state.first, state.second)
    }

    private fun notification(status: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("ChrissySDR")
        .setContentText(status)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "Stop",
            PendingIntent.getService(
                this,
                1,
                Intent(this, RadioService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val NOTIFICATION_CHANNEL = "active_radio"
        private const val NOTIFICATION_ID = 1500
        private const val ACTION_STOP = "com.kb1jdx.chrissysdr.STOP_RADIO"
    }
}

private fun formatFrequency(hz: Double): String = if (hz >= 1_000_000) {
    "%.6f MHz".format(hz / 1_000_000)
} else {
    "%.3f kHz".format(hz / 1_000)
}
