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
import android.widget.Toast
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the MediaProjection token and runs the AutomationEngine.
 *
 * Three-state lifecycle:
 *   PREPARING  Service is foreground (satisfying the Android 14 requirement) while the
 *              system consent dialog is open.  No projection yet.
 *   READY      Consent was granted; ScreenshotService is initialised and holds the
 *              VirtualDisplay.  The capture loop is NOT running.
 *   RUNNING    The coroutine capture loop is active.  Transitions back to READY on pause.
 *
 * Action flow:
 *   1. ACTION_PREPARE  — enter foreground before showing the consent dialog (Android 14).
 *   2. ACTION_READY    — consent granted; store token, enter READY state.
 *   3. ACTION_RUN      — user tapped FAB on; start the capture loop.
 *   4. ACTION_PAUSE    — user tapped FAB off; stop the loop, stay READY.
 *   5. ACTION_STOP     — disable overlay; release projection and stop foreground.
 */
class AutomationService : Service() {

    /** Scan frequency mode — declared at class level so it is resolvable as AutomationService.ScanMode. */
    enum class ScanMode { HOUSE, CAR }

    private var screenshotService: ScreenshotService? = null

    // Scope + engine are recreated on each ACTION_RUN so a cancelled scope is never reused.
    private var scope: CoroutineScope? = null
    private var automationEngine: AutomationEngine? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            ACTION_PREPARE -> {
                // Must enter foreground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION before
                // calling createScreenCaptureIntent(); otherwise createVirtualDisplay() will
                // throw SecurityException on Android 14+.
                ServiceCompat.startForeground(
                    this,
                    BackpackerApp.NOTIFICATION_ID,
                    buildPreparingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                Log.i(TAG, "PREPARING — awaiting consent")
            }

            ACTION_READY -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (resultCode == Int.MIN_VALUE || resultData == null) {
                    Log.e(TAG, "ACTION_READY: missing MediaProjection data")
                    Toast.makeText(this, "Error: bad MediaProjection data", Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }

                enterReadyState(resultCode, resultData)
            }

            ACTION_RUN -> {
                if (screenshotService != null) {
                    val modeName = intent.getStringExtra(EXTRA_MODE) ?: ScanMode.HOUSE.name
                    stopLoop()   // replace any existing loop (mode may have changed)
                    startLoop(ScanMode.valueOf(modeName))
                } else {
                    Log.e(TAG, "ACTION_RUN ignored — not in READY state")
                }
            }

            ACTION_PAUSE -> {
                stopLoop()
                TapperService.instance?.notifyAutomationStopped()
            }

            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received")
                stopLoop()
                TapperService.instance?.notifyAutomationStopped()
                releaseAll()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        TapperService.instance?.notifyAutomationStopped()
        releaseAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // State transitions
    // -------------------------------------------------------------------------

    /**
     * PREPARING → READY.
     * Creates [ScreenshotService] (and the underlying VirtualDisplay) but does not start
     * the capture loop.  Switches the notification to the "overlay active" text.
     */
    private fun enterReadyState(resultCode: Int, resultData: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection = mpManager.getMediaProjection(resultCode, resultData)

        screenshotService = ScreenshotService(this, mediaProjection) {
            // Called on the main thread when the OS revokes the projection.
            Log.w(TAG, "MediaProjection revoked — stopping")
            stopLoop()
            releaseAll()
            stopSelf()
            TapperService.instance?.hideOverlay()
        }

        isReady = true
        ServiceCompat.startForeground(
            this,
            BackpackerApp.NOTIFICATION_ID,
            buildReadyNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        Log.i(TAG, "READY — MediaProjection obtained, VirtualDisplay live")
    }

    /**
     * READY → RUNNING.
     * Creates a fresh [CoroutineScope] and [AutomationEngine] and starts the capture loop.
     * [mode] controls how frequently the loop captures; defaults to [ScanMode.HOUSE].
     */
    private fun startLoop(mode: ScanMode = ScanMode.HOUSE) {
        val tapper = TapperService.instance
        if (tapper == null) {
            Log.e(TAG, "startLoop: TapperService not connected")
            return
        }

        val intervalMs = when (mode) {
            ScanMode.HOUSE -> 60_000L   // sitting still — scan once per minute
            ScanMode.CAR   ->  1_000L   // driving — scan once per second
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        automationEngine = AutomationEngine(screenshotService!!, tapper, this, intervalMs)

        scope!!.launch { automationEngine!!.run() }

        isRunning = true
        ServiceCompat.startForeground(
            this,
            BackpackerApp.NOTIFICATION_ID,
            buildRunningNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        Log.i(TAG, "RUNNING — capture loop started")
    }

    /**
     * RUNNING → READY.
     * Cancels the coroutine scope and engine; leaves [ScreenshotService] and projection intact
     * so the loop can be restarted without a new consent dialog.
     *
     * Does NOT reset the FAB — callers that intend to pause (not switch modes) must call
     * [TapperService.notifyAutomationStopped] themselves after this returns.
     */
    private fun stopLoop() {
        if (!isRunning) return
        automationEngine?.stop()
        automationEngine?.release()
        automationEngine = null
        scope?.cancel()
        scope = null
        isRunning = false
        if (isReady) {
            ServiceCompat.startForeground(
                this,
                BackpackerApp.NOTIFICATION_ID,
                buildReadyNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
        Log.i(TAG, "Loop stopped — back to READY")
    }

    /**
     * READY → stopped.
     * Releases [ScreenshotService] and the MediaProjection token.
     * Always call [stopLoop] first.
     */
    private fun releaseAll() {
        screenshotService?.release()
        screenshotService = null
        isReady = false
        Log.i(TAG, "Projection released")
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    private fun mainActivityIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildPreparingNotification(): Notification =
        NotificationCompat.Builder(this, BackpackerApp.CHANNEL_ID)
            .setContentTitle("Backpacker")
            .setContentText("Requesting screen capture permission…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityIntent())
            .setOngoing(true).setSilent(true)
            .build()

    private fun buildReadyNotification(): Notification =
        NotificationCompat.Builder(this, BackpackerApp.CHANNEL_ID)
            .setContentTitle("Backpacker")
            .setContentText("Ready - tap the button to activate")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityIntent())
            .setOngoing(true).setSilent(true)
            .build()

    private fun buildRunningNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AutomationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, BackpackerApp.CHANNEL_ID)
            .setContentTitle("Backpacker Running")
            .setContentText("Capture loop active…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainActivityIntent())
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true).setSilent(true)
            .build()
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG = "Backpacker.AutomationService"

        const val ACTION_PREPARE = "io.github.joeyparrish.backpacker.ACTION_PREPARE"
        const val ACTION_READY   = "io.github.joeyparrish.backpacker.ACTION_READY"
        const val ACTION_RUN     = "io.github.joeyparrish.backpacker.ACTION_RUN"
        const val ACTION_PAUSE   = "io.github.joeyparrish.backpacker.ACTION_PAUSE"
        const val ACTION_STOP    = "io.github.joeyparrish.backpacker.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_MODE        = "extra_mode"

        /** True while the capture loop coroutine is running. */
        @Volatile var isRunning = false
            private set

        /** True while a MediaProjection token is held (loop may or may not be active). */
        @Volatile var isReady = false
            private set

        /** Step 1: call before showing the consent dialog to satisfy Android 14 timing. */
        fun prepare(context: Context) {
            context.startForegroundService(
                Intent(context, AutomationService::class.java).apply { action = ACTION_PREPARE }
            )
        }

        /** Step 2: call with the consent result to store the token and enter READY state. */
        fun ready(context: Context, resultCode: Int, resultData: Intent) {
            context.startForegroundService(
                Intent(context, AutomationService::class.java).apply {
                    action = ACTION_READY
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, resultData)
                }
            )
        }

        /** Start (or switch) the capture loop.  No-op if not in READY state. */
        fun run(context: Context, mode: ScanMode) {
            context.startService(
                Intent(context, AutomationService::class.java).apply {
                    action = ACTION_RUN
                    putExtra(EXTRA_MODE, mode.name)
                }
            )
        }

        /** Pause the capture loop.  Projection is kept so [run] can restart without consent. */
        fun pause(context: Context) {
            context.startService(
                Intent(context, AutomationService::class.java).apply { action = ACTION_PAUSE }
            )
        }

        /** Release the projection and stop the foreground service entirely. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, AutomationService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
