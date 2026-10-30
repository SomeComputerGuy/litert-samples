package io.github.somecomputerguy.remoteairesponder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Keeps a model download running while the screen is off or the app is in the background,
 * where Android would otherwise freeze the process and the download would time out.
 * The download itself is done by [ModelDownloader]; this service only keeps the process
 * alive and awake, and shows progress in a notification.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val EXTRA_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "model_download"
        private const val PROGRESS_NOTIFICATION_ID = 8103
        private const val RESULT_NOTIFICATION_ID = 8104
        // A safety limit; a 3 GB download normally takes minutes.
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        /** Call from the foreground app; Android doesn't allow starting this from the background. */
        fun start(context: Context, model: ModelConfig) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ModelDownloadService::class.java).putExtra(EXTRA_MODEL_ID, model.id)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloading = mutableSetOf<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val model = (ModelResolver.offeredModels(this) + ModelResolver.AVAILABLE_MODELS)
            .firstOrNull { it.id == modelId && it.downloadUrl != null }

        // startForegroundService() requires startForeground() even if there's nothing to do.
        showProgress(model?.name ?: "model", null)
        if (model == null) {
            finishIfIdle()
            return START_NOT_STICKY
        }

        acquireLocks()
        if (downloading.add(model.id)) {
            scope.launch {
                val result = ModelDownloader.download(this@ModelDownloadService, model)
                    .onEach { state ->
                        if (state is ModelDownloader.State.Running) showProgress(model.name, state)
                    }
                    .first { it is ModelDownloader.State.Done || it is ModelDownloader.State.Failed }
                downloading.remove(model.id)
                showResult(model, result)
                finishIfIdle()
            }
        }
        // If the process dies, reopening the app resumes the download from where it stopped.
        return START_NOT_STICKY
    }

    private fun finishIfIdle() {
        if (downloading.isNotEmpty()) return
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    // Android 15+ limits dataSync services to 6 hours a day; give up cleanly if we hit it.
    override fun onTimeout(startId: Int, fgsType: Int) {
        downloading.forEach { ModelDownloader.cancel(it) }
        downloading.clear()
        finishIfIdle()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteAI:download")
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION") // HIGH_PERF is the mode that still applies with the screen off.
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteAI:download")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ---------------------------------------------------------------- Notifications

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun showProgress(modelName: String, state: ModelDownloader.State.Running?) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $modelName")
            .setContentText(state?.describe() ?: "Starting…")
            .setProgress(1000, state?.permille ?: 0, state == null)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun showResult(model: ModelConfig, result: ModelDownloader.State?) {
        val (title, text) = if (result is ModelDownloader.State.Failed) {
            "Download of ${model.name} failed" to "${result.message}. Open the app to retry; it resumes where it stopped."
        } else {
            "${model.name} downloaded" to "Open the app to start the API."
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_NOTIFICATION_ID, notification)
    }
}
