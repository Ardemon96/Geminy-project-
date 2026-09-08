package com.example.timetracker

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.timetracker.data.ActivityLog
import com.example.timetracker.data.AppDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class TimerService : Service() {

    private val binder = TimerBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val timeInSeconds = MutableStateFlow(0L)
    val isRunning = MutableStateFlow(false)

    var currentTitle = ""
    var currentCategory = "Работа"

    private var timerJob: Job? = null

    inner class TimerBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
            val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "Работа"
            startTimerInternal(title, category)
        } else if (action == ACTION_STOP) {
            stopAndSaveTimer()
        }
        return START_STICKY
    }

    fun startTimerInternal(title: String, category: String) {
        currentTitle = title
        currentCategory = category
        isRunning.value = true
        
        startForeground(NOTIFICATION_ID, buildNotification(timeInSeconds.value))

        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isRunning.value) {
                delay(1000L)
                timeInSeconds.value++
                updateNotification(timeInSeconds.value)
            }
        }
    }

    fun pauseTimer() {
        isRunning.value = false
        timerJob?.cancel()
        updateNotification(timeInSeconds.value)
    }

    fun stopAndSaveTimer() {
        val secondsToSave = timeInSeconds.value
        val titleToSave = currentTitle.ifBlank { "Без названия" }
        val categoryToSave = currentCategory

        pauseTimer()
        timeInSeconds.value = 0L

        if (secondsToSave > 0) {
            serviceScope.launch {
                val dao = AppDatabase.getDatabase(applicationContext).activityDao()
                dao.insertLog(
                    ActivityLog(
                        title = titleToSave,
                        category = categoryToSave,
                        durationSeconds = secondsToSave,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (timeInSeconds.value > 0) {
            stopAndSaveTimer()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (timeInSeconds.value > 0) {
            stopAndSaveTimer()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(seconds: Long) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(seconds))
    }

    private fun buildNotification(totalSeconds: Long): Notification {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        val formatted = String.format("%02d:%02d:%02d", hours, minutes, secs)

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Таймер: $formatted")
            .setContentText("Категория: $currentCategory | $currentTitle")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Activity Timer Tracker",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "activity_timer_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_CATEGORY = "EXTRA_CATEGORY"
    }
}