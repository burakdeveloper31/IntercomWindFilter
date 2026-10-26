package com.intercom.windfilter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.abs

class WindNoiseFilter(
    private val sampleRate: Int = 16000,
    private val cutoffFrequency: Float = 180.0f,
    private val gateThreshold: Float = 450.0f
) {
    private var a0: Float = 1f
    private var a1: Float = 0f
    private var a2: Float = 0f
    private var b1: Float = 0f
    private var b2: Float = 0f

    private var x1: Float = 0f
    private var x2: Float = 0f
    private var y1: Float = 0f
    private var y2: Float = 0f

    private var envelope: Float = 0f
    private val attackAlpha: Float = 0.35f
    private val releaseAlpha: Float = 0.05f

    init {
        val omega = (2.0 * PI * cutoffFrequency / sampleRate).toFloat()
        val cosOmega = kotlin.math.cos(omega)
        val sinOmega = kotlin.math.sin(omega)
        val q = 0.7071f
        val alpha = sinOmega / (2.0f * q)

        val b0 = (1.0f + cosOmega) / 2.0f
        val b1Temp = -(1.0f + cosOmega)
        val b2Temp = (1.0f + cosOmega) / 2.0f
        val a0Temp = 1.0f + alpha
        val a1Temp = -2.0f * cosOmega
        val a2Temp = 1.0f - alpha

        a0 = b0 / a0Temp
        a1 = b1Temp / a0Temp
        a2 = b2Temp / a0Temp
        b1 = a1Temp / a0Temp
        b2 = a2Temp / a0Temp
    }

    fun process(buffer: ShortArray, length: Int) {
        for (i in 0 until length) {
            val inputSample = buffer[i].toFloat()

            val hpSample = a0 * inputSample + a1 * x1 + a2 * x2 - b1 * y1 - b2 * y2
            x2 = x1
            x1 = inputSample
            y2 = y1
            y1 = hpSample

            val absVal = abs(hpSample)
            envelope = if (absVal > envelope) {
                envelope * (1.0f - attackAlpha) + absVal * attackAlpha
            } else {
                envelope * (1.0f - releaseAlpha) + absVal * releaseAlpha
            }

            val gain = if (envelope > gateThreshold) {
                1.0f
            } else {
                0.30f + (envelope / gateThreshold) * 0.70f
            }

            var outputSample = hpSample * gain

            if (outputSample > Short.MAX_VALUE) outputSample = Short.MAX_VALUE.toFloat()
            if (outputSample < Short.MIN_VALUE) outputSample = Short.MIN_VALUE.toFloat()

            buffer[i] = outputSample.toInt().toShort()
        }
    }
}

class IntercomFilterService : Service() {

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "intercom_noise_filter_channel"
        const val SAMPLE_RATE = 16000
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null

    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var autoGain: AutomaticGainControl? = null

    private val filter = WindNoiseFilter(sampleRate = SAMPLE_RATE, cutoffFrequency = 180f, gateThreshold = 450f)
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    @Volatile private var isRunning = false

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED == intent?.action) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                Log.d("IntercomFilter", "Bluetooth SCO Durumu: $state")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        acquireWakeLock()
        createNotificationChannel()
        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Kask Mikrofonu Gürültü Filtresi Devrede")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        enableBluetoothHardwareFiltering()
        return START_STICKY
    }

    private fun enableBluetoothHardwareFiltering() {
        if (isRunning) return
        isRunning = true

        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.startBluetoothSco()
            audioManager?.isBluetoothScoOn = true

            serviceScope.launch(Dispatchers.IO) {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(1024)

                if (ActivityCompat.checkSelfPermission(this@IntercomFilterService, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    return@launch
                }

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )

                val audioSessionId = audioRecord?.audioSessionId ?: 0

                if (NoiseSuppressor.isAvailable() && audioSessionId != 0) {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                        enabled = true
                    }
                }

                if (AcousticEchoCanceler.isAvailable() && audioSessionId != 0) {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                        enabled = true
                    }
                }

                if (AutomaticGainControl.isAvailable() && audioSessionId != 0) {
                    autoGain = AutomaticGainControl.create(audioSessionId)?.apply {
                        enabled = true
                    }
                }

                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)

                while (isRunning && isActive) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        filter.process(buffer, read)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IntercomFilter", "Filtre başlatma hatası: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "IntercomFilter::WakeLockTag"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Intercom Filtre Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Kask rüzgar gürültüsünü filtreler."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Motosiklet Intercom Filtresi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serviceJob.cancel()

        try {
            noiseSuppressor?.release()
            echoCanceler?.release()
            autoGain?.release()

            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
            audioRecord = null

            audioManager?.apply {
                isBluetoothScoOn = false
                stopBluetoothSco()
                mode = AudioManager.MODE_NORMAL
            }
            unregisterReceiver(scoReceiver)
        } catch (e: Exception) {
            Log.e("IntercomFilter", "Kapatma hatası: ${e.message}")
        }

        wakeLock?.let { if (it.isHeld) it.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
