package com.intercom.windfilter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
