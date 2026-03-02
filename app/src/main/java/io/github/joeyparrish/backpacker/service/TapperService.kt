package io.github.joeyparrish.backpacker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.joeyparrish.backpacker.ui.OverlayView

/**
 * AccessibilityService that:
 *   1. Injects tap and swipe gestures into any foreground app (including Pokémon GO)
 *   2. Draws the floating ▶/⏹ overlay button over Pokémon GO
 *
 * The user must enable this service manually in Android Settings → Accessibility.
 * MainActivity provides a deep-link button to open the Accessibility settings page.
 */
class TapperService : AccessibilityService() {

    private var overlayView: OverlayView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TapperService connected")

        try {
            overlayView = OverlayView(this) { isRunning -> handleToggle(isRunning) }
            overlayView?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: $e")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TapperService does not process accessibility events — it only injects gestures.
    }

    override fun onInterrupt() {
        Log.w(TAG, "TapperService interrupted")
    }

    override fun onDestroy() {
        try { overlayView?.hide() } catch (e: Exception) { Log.w(TAG, "hide overlay: $e") }
        overlayView = null
        instance = null
        Log.i(TAG, "TapperService destroyed")
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Gesture injection
    // -------------------------------------------------------------------------

    fun tap(x: Float, y: Float) {
        Log.d(TAG, "tap($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        Log.d(TAG, "swipe($x1,$y1 → $x2,$y2, ${durationMs}ms)")
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // -------------------------------------------------------------------------

    private fun handleToggle(startAutomation: Boolean) {
        if (startAutomation) {
            overlayView?.setState(OverlayView.State.RUNNING)
            // MediaProjection consent must come from an Activity, so re-launch MainActivity.
            val launchIntent = packageManager
                .getLaunchIntentForPackage(packageName)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_START_FROM_OVERLAY, true)
                }
            launchIntent?.let { startActivity(it) }
        } else {
            overlayView?.setState(OverlayView.State.IDLE)
            AutomationService.stop(this)
        }
    }

    /**
     * Called by AutomationService when it stops (cleanly or via onDestroy) so the
     * overlay FAB is reset to IDLE.  Without this the button stays full-opacity
     * after a crash and the user has to toggle the a11y service to recover.
     */
    fun notifyAutomationStopped() {
        overlayView?.setState(OverlayView.State.IDLE)
    }

    companion object {
        private const val TAG = "TapperService"
        const val EXTRA_START_FROM_OVERLAY = "start_from_overlay"

        @Volatile
        var instance: TapperService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}
