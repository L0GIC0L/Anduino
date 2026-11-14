package com.l0gic0l.anduino

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.l0gic0l.anduino.ui.AppRoot
import com.l0gic0l.anduino.ui.theme.AnduinoTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as AnduinoApp).container

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askRuntimePermissions()
        setContent {
            val theme by container.prefs.themeMode.flow.collectAsStateWithLifecycle()
            val dynamic by container.prefs.dynamicColor.flow.collectAsStateWithLifecycle()
            AnduinoTheme(theme, dynamic) {
                AppRoot(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // USB_DEVICE_ATTACHED arrives here while the app is open.
        container.usbPorts.refresh()
    }

    override fun onResume() {
        super.onResume()
        container.usbPorts.refresh()
        // Network changes (Wi-Fi <-> mobile) change DNS servers.
        if (container.envReady.value) runCatching { container.env.writeResolvConf(container.dnsServers()) }
    }

    /** Notifications show build progress; local-network access is needed for OTA uploads on Android 17+. */
    private fun askRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray())
    }
}
