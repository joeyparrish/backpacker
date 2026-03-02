package io.github.joeyparrish.backpacker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.joeyparrish.backpacker.BackpackerApp
import io.github.joeyparrish.backpacker.R
import io.github.joeyparrish.backpacker.automation.AutomationEngine
import io.github.joeyparrish.backpacker.ui.MainActivity
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the MediaProjection token and runs the AutomationEngine.
 *
 * Android 14 (API 34) requires that the foreground service with
 * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is already running and has called startForeground()
 * BEFORE the MediaProjection consent dialog (createScreenCaptureIntent) is shown.
 *
 * Lifecycle:
 *   1. Caller sends ACTION_PREPARE before showing the consent dialog.
 *      The service enters foreground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION so the OS
 *      allows the upcoming createVirtualDisplay() call.
 *   2. User accepts the consent dialog; caller sends ACTION_START with resultCode + data.
 *   3. Service creates ScreenshotService and AutomationEngine and starts the coroutine loop.
 *   4. ACTION_STOP (or notification action, or MediaProjection revoked) stops everything.
 */
class AutomationService : Service() {

    // Recreated on each startAutomation() so that stopping and restarting works correctly.
    // A cancelled CoroutineScope cannot launch new coroutines, so we must not reuse it.
    private var scope: CoroutineScope? = null

    private var screenshotService: ScreenshotService? = null
    private var automationEngine: AutomationEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_PREPARE -> {
                // Enter foreground with the mediaProjection type BEFORE the consent dialog is
                // shown.  This satisfies the Android 14 requirement that the service is already
                // a foreground mediaProjection service when createVirtualDisplay() is later called.
                ServiceCompat.startForeground(
                    this,
                    BackpackerApp.NOTIFICATION_ID,
                    buildPreparingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                Log.i(TAG, "AutomationService prepared — awaiting MediaProjection consent")
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (resultCode == -1 || resultData == null) {
                    Log.e(TAG, "Invalid MediaProjection data; stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Update notification now that we're actually running.
                ServiceCompat.startForeground(
                    this,
                    BackpackerApp.NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                isRunning = true
                startAutomation(resultCode, resultData)
            }

            ACTION_STOP -> {
                Log.i(TAG, "Stop action received")
                stopAutomation()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAutomation()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAutomation(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        val tapper = TapperService.instance
        if (tapper == null) {
            Log.e(TAG, "TapperService not connected — accessibility service must be enabled first")
            mediaProjection.stop()
            stopSelf()
            return
        }

        // Create a fresh scope for this run.  Reusing a cancelled scope silently drops launches.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        screenshotService = ScreenshotService(this, mediaProjection) {
            // Called on the main thread when the OS revokes the MediaProjection.
            Log.w(TAG, "MediaProjection revoked externally — stopping service")
            stopAutomation()
            stopSelf()
        }
        automationEngine = AutomationEngine(screenshotService!!, tapper)

        scope!!.launch {
            automationEngine!!.run()
        }

        // Confirm screen capture is working by capturing one frame and toasting its dimensions.
        scope!!.launch {
            delay(500) // give the VirtualDisplay time to produce its first frame
            val bmp = screenshotService?.capture()
            val msg = if (bmp != null) {
                val w = bmp.width
                val h = bmp.height
                bmp.recycle()
                "Screenshot OK: ${w}×${h}"
            } else {
                "Screenshot failed (null) — capture not ready"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@AutomationService, msg, Toast.LENGTH_LONG).show()
            }
        }

        Log.i(TAG, "Automation started")
    }

    private fun stopAutomation() {
        isRunning = false
        automationEngine?.stop()
        screenshotService?.release()
        screenshotService = null
        automationEngine = null
        scope?.cancel()
        scope = null
        // Reset the overlay FAB so it doesn't stay in the RUNNING state after
        // the service dies (which would require the user to toggle the a11y service).
        TapperService.instance?.notifyAutomationStopped()
        Log.i(TAG, "Automation stopped")
    }

    private fun buildPreparingNotification(): Notification {
        return NotificationCompat.Builder(this, BackpackerApp.CHANNEL_ID)
            .setContentTitle("Backpacker")
            .setContentText("Requesting screen capture permission…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AutomationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BackpackerApp.CHANNEL_ID)
            .setContentTitle("Backpacker Running")
            .setContentText("Spinning Pokéstops automatically…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "AutomationService"

        const val ACTION_PREPARE = "io.github.joeyparrish.backpacker.ACTION_PREPARE"
        const val ACTION_START   = "io.github.joeyparrish.backpacker.ACTION_START"
        const val ACTION_STOP    = "io.github.joeyparrish.backpacker.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile var isRunning = false
            private set

        /** Must be called before [startMediaProjectionConsent] to satisfy Android 14 timing. */
        fun prepare(context: Context) {
            context.startForegroundService(
                Intent(context, AutomationService::class.java).apply { action = ACTION_PREPARE }
            )
        }

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            context.startForegroundService(
                Intent(context, AutomationService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, resultData)
                }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AutomationService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
