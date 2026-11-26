package com.example.websms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private lateinit var tvServerAddress: TextView
    private lateinit var tvLog: TextView
    private val permissionCode = 123

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val url = intent?.getStringExtra(SmsServerService.EXTRA_SERVER_URL).orEmpty()
            val log = intent?.getStringExtra(SmsServerService.EXTRA_LOG_TEXT).orEmpty()
            if (url.isNotBlank()) tvServerAddress.text = url
            if (log.isNotBlank()) tvLog.text = log
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvServerAddress = findViewById(R.id.tvServerAddress)
        tvLog = findViewById(R.id.tvLog)

        if (SmsServerService.currentUrl.isNotBlank()) {
            tvServerAddress.text = SmsServerService.currentUrl
        }
        tvLog.text = SmsServerService.currentLog

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            statusReceiver,
            IntentFilter(SmsServerService.ACTION_STATUS_UPDATE)
        )
        if (SmsServerService.currentUrl.isNotBlank()) {
            tvServerAddress.text = SmsServerService.currentUrl
        }
        tvLog.text = SmsServerService.currentLog
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBackgroundSmsServer()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val smsAndContactsGranted = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS
        ).all { perm ->
            val idx = permissions.indexOf(perm)
            idx >= 0 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED
        }

        if (requestCode == permissionCode && smsAndContactsGranted) {
            startBackgroundSmsServer()
        } else {
            tvLog.text = "Sovellus tarvitsee luvan yhteystietoihin sekä tekstiviestien lukuun ja lähetykseen."
        }
    }

    private fun startBackgroundSmsServer() {
        val serviceIntent = Intent(this, SmsServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}