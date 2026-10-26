package com.intercom.windfilter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class IntercomFilterService : Service() {

    private val channelId = "intercom_filter_channel"
    private var isRunning = false
    private var audioRecord: AudioRecord? = null
    private lateinit var audioManager: AudioManager

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)

        startBluetoothScoAndFiltering()
        return START_STICKY
    }

    private fun startBluetoothScoAndFiltering() {
        if (isRunning) return
        isRunning = true

        // Bluetooth SCO (Kask mikrofonu) modunu aç
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            audioRecord?.startRecording()

            // Arka planda filtreleme döngüsü
            thread {
                val audioBuffer = ShortArray(bufferSize / 2)
                var lastSample = 0f
                val alpha = 0.85f // Basit High-Pass Rüzgar Filtresi katsayısı

                while (isRunning) {
                    val readSize = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (readSize > 0) {
                        for (i in 0 until readSize) {
                            val current = audioBuffer[i].toFloat()
                            // 150 Hz altındaki rüzgar ve uğultuyu kesen filtreleme
                            val filtered = current - lastSample + (alpha * lastSample)
                            lastSample = current
                            
                            // Gürültü kapısı (Konuşma yoksa rüzgarı tamamen sustur)
                            audioBuffer[i] = if (kotlin.math.abs(filtered) < 400) 0 else filtered.toInt().toShort()
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun stopFiltering() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopFiltering()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Kask Gürültü Filtresi",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Kask Gürültü Filtresi Aktif")
            .setContentText("Intercom mikrofonu arka planda filtreleniyor.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }
}
