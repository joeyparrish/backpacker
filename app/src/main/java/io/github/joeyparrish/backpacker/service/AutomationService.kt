package io.github.joeyparrish.backpacker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.joeyparrish.backpacker.BackpackerApp
import io.github.joeyparrish.backpacker.R
import io.github.joeyparrish.backpacker.automation.AutomationEngine
import io.github.joeyparrish.backpacker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the MediaProjection token and runs the AutomationEngine.
 *
 * Lifecycle:
 *   1. MainActivity obtains the MediaProjection consent Intent.
 *   2. MainActivity calls [start], passing resultCode and data Intent.
 *   3. This service calls startForeground immediately, then creates ScreenshotService
 *      and AutomationEngine and starts the coroutine loop.
 *   4. [stop] (or the notification action) cancels the coroutine and releases resources.
 */
class AutomationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var screenshotService: ScreenshotService? = null
    private var automationEngine: AutomationEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (resultCode == -1 || resultData == null) {
                    Log.e(TAG, "Invalid MediaProjection data; stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // On Android 10+ (API 29) startForeground must declare the service type;
                // on Android 14+ this is strictly enforced for mediaProjection services.
                // ServiceCompat handles the version check automatically.
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

        screenshotService = ScreenshotService(this, mediaProjection) {
            // Called on the main thread when the OS revokes the MediaProjection.
            Log.w(TAG, "MediaProjection revoked externally — stopping service")
            stopAutomation()
            stopSelf()
        }
        automationEngine = AutomationEngine(screenshotService!!, tapper)

        scope.launch {
            automationEngine!!.run()
        }

        Log.i(TAG, "Automation started")
    }

    private fun stopAutomation() {
        isRunning = false
        automationEngine?.stop()
        screenshotService?.release()
        screenshotService = null
        automationEngine = null
        scope.cancel()
        Log.i(TAG, "Automation stopped")
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

        const val ACTION_START = "io.github.joeyparrish.backpacker.ACTION_START"
        const val ACTION_STOP  = "io.github.joeyparrish.backpacker.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        @Volatile var isRunning = false
            private set

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
