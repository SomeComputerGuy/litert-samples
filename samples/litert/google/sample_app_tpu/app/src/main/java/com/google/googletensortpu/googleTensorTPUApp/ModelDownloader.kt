package com.google.googletensortpu.googleTensorTPUApp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads models that have a [ModelConfig.downloadUrl] into app-private storage.
 * Downloads live at process scope so they survive Activity recreation, and an
 * interrupted download resumes from its .part file on the next attempt.
 */
object ModelDownloader {

    sealed interface State {
        data class Running(
            val downloadedBytes: Long,
            val totalBytes: Long,
            // Smoothed recent rate; 0 until the first measurement.
            val bytesPerSecond: Long = 0L
        ) : State {
            val secondsLeft: Long?
                get() = if (bytesPerSecond > 0) (totalBytes - downloadedBytes) / bytesPerSecond else null
        }
        object Done : State
        data class Failed(val message: String) : State
    }

    private const val TAG = "ModelDownloader"
    private const val BUFFER_BYTES = 1 shl 20
    private const val PROGRESS_INTERVAL_MS = 500L
    // Weight of the newest sample in the speed average; lower is steadier.
    private const val RATE_SMOOTHING = 0.3
    private const val FREE_SPACE_MARGIN_BYTES = 256L shl 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val states = mutableMapOf<String, MutableStateFlow<State?>>()

    /** Starts the download unless one is already running, and returns its state. */
    @Synchronized
    fun download(context: Context, model: ModelConfig): StateFlow<State?> {
        val state = states.getOrPut(model.id) { MutableStateFlow(null) }
        if (jobs[model.id]?.isActive == true) return state

        // Clear a stale Done/Failed from an earlier attempt before observers see it.
        state.value = null
        val appContext = context.applicationContext
        jobs[model.id] = scope.launch {
            try {
                fetch(appContext, model, state)
                state.value = State.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download of ${model.filename} failed", e)
                state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        return state
    }

    @Synchronized
    fun cancel(modelId: String) {
        jobs.remove(modelId)?.cancel()
    }

    private suspend fun fetch(context: Context, model: ModelConfig, state: MutableStateFlow<State?>) {
        val url = model.downloadUrl ?: throw IllegalArgumentException("${model.name} has no download URL")
        val total = model.sizeBytes
        val target = ModelResolver(context).downloadedModelFile(model)
        val dir = target.parentFile!!
        dir.mkdirs()
        val part = File(target.path + ".part")

        var offset = if (part.length() in 1..total) part.length() else 0L
        val needed = total - offset
        if (dir.usableSpace < needed + FREE_SPACE_MARGIN_BYTES) {
            throw IOException(
                "Not enough free space: need %.1f GB, have %.1f GB"
                    .format(needed / 1e9, dir.usableSpace / 1e9)
            )
        }

        state.value = State.Running(offset, total)
        Log.i(TAG, "Downloading ${model.filename} from byte $offset of $total")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")

        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                // The server ignored the range, so the body starts at byte 0.
                HttpURLConnection.HTTP_OK -> offset = 0L
                else -> throw IOException("HTTP $code while downloading ${model.filename}")
            }

            conn.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReportedBytes = offset
                    var lastReportedAt = System.nanoTime()
                    var rate = 0.0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        offset += count

                        val now = System.nanoTime()
                        val elapsedMs = (now - lastReportedAt) / 1_000_000
                        if (elapsedMs >= PROGRESS_INTERVAL_MS) {
                            val sample = (offset - lastReportedBytes) * 1000.0 / elapsedMs
                            rate = if (rate == 0.0) sample else rate + RATE_SMOOTHING * (sample - rate)
                            state.value = State.Running(offset, total, rate.toLong())
                            lastReportedBytes = offset
                            lastReportedAt = now
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (part.length() != total) {
            throw IOException(
                "Download stopped at %.2f of %.2f GB; retry to resume"
                    .format(part.length() / 1e9, total / 1e9)
            )
        }
        if (!part.renameTo(target)) {
            throw IOException("Could not move ${part.name} into place")
        }
        Log.i(TAG, "Downloaded ${model.filename} to ${target.absolutePath}")
    }
}
