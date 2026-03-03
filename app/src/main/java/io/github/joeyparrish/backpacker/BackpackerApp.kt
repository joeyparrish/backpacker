package io.github.joeyparrish.backpacker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader

class BackpackerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initOpenCV()
    }

    private fun initOpenCV() {
        @Suppress("DEPRECATION")
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV loaded successfully")
        } else {
            Log.e(TAG, "OpenCV failed to load")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Backpacker Automation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Pokéstop automation is running in the background"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "Backpacker.BackpackerApp"
        const val CHANNEL_ID = "backpacker_service"
        const val NOTIFICATION_ID = 1001
    }
}
