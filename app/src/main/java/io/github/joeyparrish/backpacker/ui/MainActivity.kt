package io.github.joeyparrish.backpacker.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.joeyparrish.backpacker.R
import io.github.joeyparrish.backpacker.databinding.ActivityMainBinding
import io.github.joeyparrish.backpacker.service.AutomationService
import io.github.joeyparrish.backpacker.service.TapperService

/**
 * Onboarding + control activity.
 *
 * Walks the user through three required permissions before offering the Start button:
 *   1. Accessibility service enabled (TapperService)
 *   2. POST_NOTIFICATIONS permission (Android 13+)
 *   3. Battery optimization disabled
 *
 * The Start button triggers the MediaProjection consent dialog; on approval the
 * AutomationService is started with the resulting token.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val mpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AutomationService.start(this, result.resultCode, result.data!!)
            updateToggleButton()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notification permission denied — service may be killed",
                Toast.LENGTH_LONG
            ).show()
        }
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnRequestNotification.setOnClickListener { requestNotificationPermission() }
        binding.btnBattery.setOnClickListener { openBatteryOptimizationSettings() }
        binding.btnToggle.setOnClickListener { handleToggleTap() }

        handleStartFromOverlay(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleStartFromOverlay(intent)
    }

    private fun handleStartFromOverlay(intent: Intent?) {
        if (intent?.getBooleanExtra(TapperService.EXTRA_START_FROM_OVERLAY, false) == true) {
            if (isAccessibilityEnabled()) startMediaProjectionConsent()
        }
    }

    private fun handleToggleTap() {
        if (AutomationService.isRunning) {
            AutomationService.stop(this)
            updateToggleButton()
        } else {
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "Enable the Accessibility service first", Toast.LENGTH_LONG).show()
                return
            }
            startMediaProjectionConsent()
        }
    }

    private fun startMediaProjectionConsent() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mpLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expectedComponent = ComponentName(this, TapperService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedComponent.flattenToString())
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Cannot open Accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Toast.makeText(this, "Not required on this Android version", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateStatus() {
        val a11yOk = isAccessibilityEnabled()
        val notifOk = hasNotificationPermission()
        val batteryOk = isBatteryOptimizationDisabled()

        binding.tvAccessibilityStatus.text = if (a11yOk)
            getString(R.string.status_accessibility_ok)
        else
            getString(R.string.status_accessibility_missing)

        binding.btnOpenAccessibility.isEnabled = !a11yOk
        binding.btnRequestNotification.isEnabled = !notifOk
        binding.btnBattery.isEnabled = !batteryOk
        binding.btnToggle.isEnabled = a11yOk
        updateToggleButton()

        Log.d(TAG, "Status — a11y=$a11yOk  notif=$notifOk  battery=$batteryOk  running=${AutomationService.isRunning}")
    }

    private fun updateToggleButton() {
        binding.btnToggle.text = if (AutomationService.isRunning)
            getString(R.string.stop_automation)
        else
            getString(R.string.start_automation)
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
