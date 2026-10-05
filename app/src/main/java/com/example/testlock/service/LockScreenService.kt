package com.example.testlock.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class LockScreenService : Service() {

    private var viewBinder: LockScreenViewBinder? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var isScreenOff = false

    companion object {
        private const val TAG = "LockScreenService"
        const val CHANNEL_ID = "LockScreenServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        viewBinder = LockScreenViewBinder(this) {
            Log.d(TAG, "Unlock requested callback received from ViewBinder")
            hideLockScreen()
        }

        registerScreenReceiver()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Lock Screen Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TestLock Service đang chạy")
            .setContentText("Lắng nghe sự kiện màn hình khóa/tắt.")
            .setSmallIcon(R.drawable.ic_lock_lock)
            .build()
    }

    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "BroadcastReceiver onReceive: action = ${intent?.action}")
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "ACTION_SCREEN_OFF received. Screen is turning off.")
                        isScreenOff = true
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "ACTION_SCREEN_ON received. Screen is turning on. Showing lock screen if screen was off.")
                        if (isScreenOff) {
                            showLockScreen()
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.d(TAG, "ACTION_USER_PRESENT received. Hiding lock screen.")
//                        isScreenOff = false
//                        hideLockScreen()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        Log.d(TAG, "ScreenReceiver registered successfully.")
    }

    private fun showLockScreen() {
        Log.d(TAG, "showLockScreen()")
        viewBinder?.show()
    }

    private fun hideLockScreen() {
        Log.d(TAG, "hideLockScreen()")
        viewBinder?.hide()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        hideLockScreen()
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "ScreenReceiver unregistered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
        }
    }
}
