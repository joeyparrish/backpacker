// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

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
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.joeyparrish.backpacker.R
import io.github.joeyparrish.backpacker.automation.AutomationEngine
import io.github.joeyparrish.backpacker.databinding.ActivityMainBinding
import io.github.joeyparrish.backpacker.service.AutomationService
import io.github.joeyparrish.backpacker.service.TapperService

/**
 * Onboarding + control activity.
 *
 * Walks the user through required permissions, then offers a switch that:
 *   1. Prepares the AutomationService foreground service (Android 14 timing requirement).
 *   2. Shows the MediaProjection consent dialog.
 *   3. On grant: stores the token (READY state) and shows the floating FAB overlay.
 *
 * Once the overlay is active the user interacts with the FAB directly.  The FAB toggles
 * the capture loop (RUNNING ↔ READY) without needing to return to this activity.
 *
 * Toggling the switch off hides the FAB and releases the MediaProjection token.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * Guards against [updateOverlaySwitch] triggering [setOnCheckedChangeListener]
     * when we programmatically update the switch state.
     */
    private var updatingOverlaySwitch = false

    /**
     * Guards against the debug switch listeners triggering each other when we
     * programmatically clear one switch while enabling the other.
     */
    private var updatingDebugSwitches = false

    /** null = not yet initialised; set once on first updateStatus() call. */
    private var setupExpanded: Boolean? = null

    private val mpLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Permission granted: enter READY state then show the overlay.
            AutomationService.ready(this, result.resultCode, result.data!!)
            TapperService.instance?.showOverlay()
            updateOverlaySwitch()
        } else {
            // User denied — clean up the prepared foreground service and reset the switch.
            AutomationService.stop(this)
            updateOverlaySwitch()
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

        binding.switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (updatingOverlaySwitch) return@setOnCheckedChangeListener
            if (checked) enableOverlay() else disableOverlay()
        }

        binding.switchDebugScan.setOnCheckedChangeListener { _, checked ->
            if (updatingDebugSwitches) return@setOnCheckedChangeListener
            AutomationEngine.debugScan = checked
            if (checked) {
                updatingDebugSwitches = true
                binding.switchSpinnerDebug.isChecked = false
                AutomationEngine.debugSpinner = false
                updatingDebugSwitches = false
            }
            OverlayView.skipCarMode = checked || binding.switchSpinnerDebug.isChecked
        }

        binding.switchSpinnerDebug.setOnCheckedChangeListener { _, checked ->
            if (updatingDebugSwitches) return@setOnCheckedChangeListener
            AutomationEngine.debugSpinner = checked
            if (checked) {
                updatingDebugSwitches = true
                binding.switchDebugScan.isChecked = false
                AutomationEngine.debugScan = false
                updatingDebugSwitches = false
            }
            OverlayView.skipCarMode = checked || binding.switchDebugScan.isChecked
        }

        binding.setupHeader.setOnClickListener {
            setupExpanded = !(setupExpanded ?: true)
            applySetupExpanded(animated = true)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // -------------------------------------------------------------------------
    // Overlay enable / disable
    // -------------------------------------------------------------------------

    private fun enableOverlay() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Enable the Accessibility service first", Toast.LENGTH_LONG).show()
            updateOverlaySwitch()   // snap switch back to off
            return
        }
        // Step 1: start the foreground service so Android 14's timing requirement is satisfied
        // before the consent dialog is shown.
        AutomationService.prepare(this)
        // Step 2: show the system screen-capture consent dialog.
        // On Android 14+ (API 34) we pass createConfigForDefaultDisplay() to skip the
        // app-vs-full-screen picker and go straight to the simple "Allow recording?" prompt.
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpManager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mpManager.createScreenCaptureIntent()
        }
        mpLauncher.launch(captureIntent)
    }

    private fun disableOverlay() {
        TapperService.instance?.hideOverlay()
        AutomationService.stop(this)
        resetDebugFlags()
        updateOverlaySwitch()
    }

    private fun resetDebugFlags() {
        updatingDebugSwitches = true
        binding.switchDebugScan.isChecked = false
        binding.switchSpinnerDebug.isChecked = false
        updatingDebugSwitches = false
        AutomationEngine.debugScan = false
        AutomationEngine.debugSpinner = false
        OverlayView.skipCarMode = false
    }

    // -------------------------------------------------------------------------
    // Status + switch state
    // -------------------------------------------------------------------------

    private fun updateStatus() {
        val a11yOk = isAccessibilityEnabled()
        val notifOk = hasNotificationPermission()
        val batteryOk = isBatteryOptimizationDisabled()

        if (setupExpanded == null) {
            setupExpanded = !(a11yOk && notifOk && batteryOk)
            applySetupExpanded(animated = false)
        }

        binding.tvAccessibilityStatus.text = if (a11yOk)
            getString(R.string.status_accessibility_ok)
        else
            getString(R.string.status_accessibility_missing)

        binding.btnOpenAccessibility.isEnabled = !a11yOk
        binding.btnRequestNotification.isEnabled = !notifOk
        binding.btnBattery.isEnabled = !batteryOk
        binding.switchOverlay.isEnabled = a11yOk
        updateOverlaySwitch()

        val prefs = getSharedPreferences(AutomationEngine.PREFS_NAME, Context.MODE_PRIVATE)
        val lifetimeSpins = prefs.getInt(AutomationEngine.PREF_LIFETIME_SPINS, 0)
        binding.tvLifetimeSpins.text = getString(R.string.lifetime_spins, lifetimeSpins)

        Log.d(TAG, "Status — a11y=$a11yOk notif=$notifOk battery=$batteryOk " +
                "overlayShown=${TapperService.isOverlayShown} " +
                "ready=${AutomationService.isReady} running=${AutomationService.isRunning}")
    }

    private fun applySetupExpanded(animated: Boolean) {
        val expanded = setupExpanded ?: return
        binding.setupContent.visibility = if (expanded) View.VISIBLE else View.GONE
        val targetRotation = if (expanded) 0f else 180f
        if (animated) {
            binding.ivSetupChevron.animate().rotation(targetRotation).setDuration(200).start()
        } else {
            binding.ivSetupChevron.rotation = targetRotation
        }
    }

    private fun updateOverlaySwitch() {
        updatingOverlaySwitch = true
        binding.switchOverlay.isChecked = TapperService.isOverlayShown
        updatingOverlaySwitch = false
    }

    // -------------------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------------------

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

    companion object {
        private const val TAG = "Backpacker.MainActivity"
    }
}
