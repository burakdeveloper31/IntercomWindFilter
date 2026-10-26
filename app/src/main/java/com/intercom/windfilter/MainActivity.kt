package com.intercom.windfilter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        statusTextView = TextView(this).apply {
            text = "Kask Gürültü Filtresi: Kapalı"
            textSize = 18f
            setPadding(0, 0, 0, 50)
        }
        layout.addView(statusTextView)

        startButton = Button(this).apply {
            text = "Filtreyi Başlat"
            setOnClickListener { checkPermissionsAndStart() }
        }
        layout.addView(startButton)

        stopButton = Button(this).apply {
            text = "Filtreyi Durdur"
            isEnabled = false
            setOnClickListener { stopFilterService() }
        }
        layout.addView(stopButton)

        setContentView(layout)
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        } else {
            startFilterService()
        }
    }

    private fun startFilterService() {
        val intent = Intent(this, IntercomFilterService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        statusTextView.text = "Kask Gürültü Filtresi: Aktif (Arka Planda)"
        startButton.isEnabled = false
        stopButton.isEnabled = true
        Toast.makeText(this, "Filtre başlatıldı", Toast.LENGTH_SHORT).show()
    }

    private fun stopFilterService() {
        val intent = Intent(this, IntercomFilterService::class.java)
        stopService(intent)
        statusTextView.text = "Kask Gürültü Filtresi: Kapalı"
        startButton.isEnabled = true
        stopButton.isEnabled = false
        Toast.makeText(this, "Filtre durduruldu", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startFilterService()
        } else {
            Toast.makeText(this, "Gerekli mikrofon/Bluetooth izinleri verilmedi!", Toast.LENGTH_LONG).show()
        }
    }
}
