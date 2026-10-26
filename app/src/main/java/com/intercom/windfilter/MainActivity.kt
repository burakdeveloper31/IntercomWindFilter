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
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.abs

/*
 =========================================================================================
 BÖLÜM 1: 2. Derece Butterworth DSP Filtresi (Rüzgar & Uğultu Kesici)
 =========================================================================================
*/
class WindNoiseFilter(
    private val sampleRate: Int = 16000,
    private val cutoffFrequency: Float = 180.0f, // 180 Hz altı rüzgar/egzoz kesilir
    private val gateThreshold: Float = 450.0f    // Eşik düşürüldü: Sesinizin kesilmesini engeller
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
    private val attackAlpha: Float = 0.35f   // Konuşma başlayınca anında açılır
    private val releaseAlpha: Float = 0.05f  // Konuşma bitince kelime sonunu yutmadan yumuşak kapanır

    init {
        calculateBiquadCoefficients()
    }

    private fun calculateBiquadCoefficients() {
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

            // 1. High-Pass Filtresi
            val hpSample = a0 * inputSample + a1 * x1 + a2 * x2 - b1 * y1 - b2 * y2
            x2 = x1
            x1 = inputSample
            y2 = y1
            y1 = hpSample

            // 2. Zarf Takibi
            val absVal = abs(hpSample)
            envelope = if (absVal > envelope) {
                envelope * (1.0f - attackAlpha) + absVal * attackAlpha
            } else {
                envelope * (1.0f - releaseAlpha) + absVal * releaseAlpha
            }

            // 3. Dynamic Gain: Sesin gitmesini engellememek için zemin seviyesi %30'a yükseltildi
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

/*
 =========================================================================================
 BÖLÜM 2: Düzeltilmiş Arka Plan Servisi ve Donanım Gürültü Engelleyicisi
 =========================================================================================
*/
class IntercomFilterService : Service() {

    private companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "intercom_noise_filter_channel"
        const val SAMPLE_RATE = 16000
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null

    // Donanımsal Ses Filtreleri (DSP)
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
                Log.d("IntercomFilter", "Bluetooth SCO Bağlantı Durumu: $state")
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
            // 1. Bluetooth Handsfree (SCO) modunu başlat
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.startBluetoothSco()
            audioManager?.isBluetoothScoOn = true

            // 2. AudioRecord başlat ve Donanımsal Gürültü Engelleyicileri (AEC / NS / AGC) bağla
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

                // VOICE_COMMUNICATION modu çağrı sırasında diğer uygulamalarla (GSM/WhatsApp) mikrofonu paylaşır
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )

                val audioSessionId = audioRecord?.audioSessionId ?: 0

                // Telefonun ve Bluetooth çipinin donanımsal gürültü kesicisini zorla aktif et
                if (NoiseSuppressor.isAvailable() && audioSessionId != 0) {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                        enabled = true
                        Log.i("IntercomFilter", "Donanımsal NoiseSuppressor (Rüzgar Önleyici) Açıldı!")
                    }
                }

                // Eko Engelleyiciyi devreye al (Kask içi hoparlör sesinin mikrofona geri kaçmasını önler)
                if (AcousticEchoCanceler.isAvailable() && audioSessionId != 0) {
                    echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                        enabled = true
                        Log.i("IntercomFilter", "Donanımsal AcousticEchoCanceler Açıldı!")
                    }
                }

                // Sesinizin kısılmasını önleyen Otomatik Kazanç Kontrolü (AGC)
                if (AutomaticGainControl.isAvailable() && audioSessionId != 0) {
                    autoGain = AutomaticGainControl.create(audioSessionId)?.apply {
                        enabled = true
                        Log.i("IntercomFilter", "Otomatik Ses Seviyesi (AGC) Açıldı!")
                    }
                }

                audioRecord?.startRecording()
                val buffer = ShortArray(bufferSize)

                // Sürekli okuma döngüsü: Donanım DSP motorunu uyanık ve aktif tutar
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

/*
 =========================================================================================
 BÖLÜM 3: Kullanıcı Arayüzü (MainActivity.kt)
 =========================================================================================
*/
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            tvStatus.text = "İzinler tamam. Kaskı bağlayıp başlatabilirsiniz."
            btnStart.isEnabled = true
        } else {
            tvStatus.text = "Mikrofon ve Bluetooth izinleri verilmedi!"
            btnStart.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        tvStatus = TextView(this).apply {
            text = "Hazır: Kask intercomu bağlıysa başlatın."
            textSize = 16f
            setPadding(0, 0, 0, 80)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }

        btnStart = Button(this).apply {
            text = "Filtreyi Başlat (Gürültü Engelleme Aktif)"
            setOnClickListener { startIntercomService() }
        }

        btnStop = Button(this).apply {
            text = "Filtreyi Durdur"
            isEnabled = false
            setOnClickListener { stopIntercomService() }
        }

        rootLayout.addView(tvStatus)
        rootLayout.addView(btnStart)
        rootLayout.addView(btnStop)
        setContentView(rootLayout)

        checkPermissions()
    }

    private fun checkPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            btnStart.isEnabled = true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startIntercomService() {
        val serviceIntent = Intent(this, IntercomFilterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        tvStatus.text = "Filtre devrede! Aramalarda sesiniz net iletilir."
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun stopIntercomService() {
        val serviceIntent = Intent(this, IntercomFilterService::class.java)
        stopService(serviceIntent)

        tvStatus.text = "Filtre kapatıldı."
        btnStart.isEnabled = true
        btnStop.isEnabled = false
    }
}
