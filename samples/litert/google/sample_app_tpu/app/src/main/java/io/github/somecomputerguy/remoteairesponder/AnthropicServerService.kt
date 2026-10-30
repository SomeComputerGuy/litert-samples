package io.github.somecomputerguy.remoteairesponder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLException

class AnthropicServerService : Service() {

    sealed interface Status {
        object Stopped : Status
        data class Starting(val modelName: String) : Status
        data class Running(val modelName: String) : Status
        object Stopping : Status
        data class Failed(val message: String) : Status
    }

    enum class LogKind { INFO, REQUEST, RESPONSE, ERROR }

    data class LogEntry(
        val timeMillis: Long,
        val kind: LogKind,
        val title: String,
        val body: String = ""
    )

    companion object {
        const val ACTION_START = "anthropic.server.START"
        const val ACTION_STOP = "anthropic.server.STOP"
        const val EXTRA_MODEL_ID = "model_id"

        const val PREFS = "AnthropicServerPrefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_TOKEN = "api_token"

        /** HTTPS only ("AIAI"). Pairing and the API share it. */
        const val PORT = 2424
        const val MDNS_SERVICE_TYPE = "_remoteai._tcp"
        private const val TAG = "AnthropicServer"
        private const val CHANNEL_ID = "anthropic_server"
        private const val NOTIFICATION_ID = 8102
        private const val PAIRING_CHANNEL_ID = "pairing_requests"
        private const val PAIRING_NOTIFICATION_ID = 8105
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val REQUEST_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        private val _status = MutableStateFlow<Status>(Status.Stopped)

        /**
         * What the server is actually doing in this process. Unlike the "enabled"
         * preference, this resets to Stopped whenever the process dies.
         */
        val status: StateFlow<Status> = _status

        private const val MAX_LOG_ENTRIES = 200
        private const val MAX_LOGGED_TEXT_CHARS = 4000

        private val _log = MutableStateFlow<List<LogEntry>>(emptyList())

        /** Recent server events and traffic, newest last, for the app's traffic view. */
        val log: StateFlow<List<LogEntry>> = _log

        private fun log(kind: LogKind, title: String, body: String = "") {
            val text = if (body.length > MAX_LOGGED_TEXT_CHARS) {
                body.take(MAX_LOGGED_TEXT_CHARS) + "\n… (${body.length - MAX_LOGGED_TEXT_CHARS} more chars)"
            } else {
                body
            }
            synchronized(_log) {
                _log.value = (_log.value + LogEntry(System.currentTimeMillis(), kind, title, text))
                    .takeLast(MAX_LOG_ENTRIES)
            }
        }

        fun clearLog() {
            synchronized(_log) { _log.value = emptyList() }
        }

        val isActive: Boolean
            get() = _status.value.let {
                it is Status.Starting || it is Status.Running || it is Status.Stopping
            }

        /** Whether the user left the API on, e.g. before an app update killed the process. */
        fun wasLeftOn(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun start(context: Context, model: ModelConfig) {
            if (isActive) return
            // Set synchronously so the UI doesn't load its own engine in the meantime.
            _status.value = Status.Starting(model.name)
            val intent = Intent(context, AnthropicServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MODEL_ID, model.id)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
                throw e
            }
        }

        fun stop(context: Context) {
            if (!isActive) return
            context.startService(Intent(context, AnthropicServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var mdnsRegistration: NsdManager.RegistrationListener? = null
    private var selectedModel: ModelConfig? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val openClients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadTensorVendorLibraries()
    }

    /** The Tensor TPU runtime needs these vendor libraries loaded into the process first. */
    private fun loadTensorVendorLibraries() {
        for (path in listOf("/vendor/lib64/libedgetpu_litert.so", "/vendor/lib64/libedgetpu_util.so")) {
            try {
                System.load(path)
                Log.i(TAG, "Loaded $path")
            } catch (e: Throwable) {
                // Expected on phones without a Tensor TPU; the GPU/CPU backends don't need it.
                Log.w(TAG, "Could not load $path: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // A null intent is a system restart after the process was killed (START_STICKY).
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
            ?: prefs.getString(KEY_MODEL_ID, null)

        if (modelId.isNullOrBlank() || !prefs.getBoolean(KEY_ENABLED, true) && intent == null) {
            _status.value = Status.Stopped
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Loading model…"))
        if (_status.value is Status.Running || serverSocket != null) {
            return START_STICKY
        }
        if (_status.value !is Status.Starting) {
            _status.value = Status.Starting(modelId)
        }

        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_MODEL_ID, modelId)
            .apply()
        acquireWifiLock()

        serviceScope.launch {
            try {
                ModelResolver.loadCustomModels(applicationContext)
                val model = ModelResolver.AVAILABLE_MODELS.firstOrNull { it.id == modelId }
                    ?: throw IllegalStateException("Saved model '$modelId' was not found.")

                selectedModel = model
                log(LogKind.INFO, "Loading ${model.name}…")
                val resolver = ModelResolver(applicationContext)
                if (!resolver.isModelAvailable(model)) {
                    throw IllegalStateException("Model file is not available: ${model.filename}")
                }

                val modelPath = resolver.resolveModelPath(model)
                val manager = LiteRTLMManager.getInstance(applicationContext)
                val result = manager.initialize(
                    modelPath = modelPath,
                    systemPrompt = model.systemPrompt,
                    preferredBackend = model.preferredBackend,
                    supportsImage = model.supportsImage,
                    supportsAudio = model.supportsAudio,
                    maxContext = model.maxContext
                )

                result.getOrThrow()
                startHttpServer(model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Server startup failed", e)
                log(LogKind.ERROR, "Server failed to start", e.message ?: e.javaClass.simpleName)
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_ENABLED, false).apply()
                shutDown(Status.Failed(e.message ?: e.javaClass.simpleName))
            }
        }

        return START_STICKY
    }

    private suspend fun startHttpServer(model: ModelConfig) = withContext(Dispatchers.IO) {
        if (serverSocket != null) return@withContext

        // Set SO_REUSEADDR before binding so a quick stop/start can rebind the port.
        val socket = ServerIdentity.createServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(PORT))
        serverSocket = socket
        _status.value = Status.Running(model.name)
        updateNotification("Running · ${model.name} · port $PORT (TLS)")
        log(
            LogKind.INFO,
            "Listening on port $PORT (TLS)",
            "${model.name} on ${LiteRTLMManager.getInstance(applicationContext).getActiveBackendName()}\n" +
                "Certificate SHA-256: ${ServerIdentity.fingerprint}"
        )
        Log.i(TAG, "Anthropic-compatible API listening on port $PORT (TLS)")
        registerMdns(model)

        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!socket.isClosed) Log.e(TAG, "Accept failed", e)
                break
            }

            openClients.add(client)
            serviceScope.launch {
                try {
                    client.use { handleClient(it) }
                } finally {
                    openClients.remove(client)
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        // Keep the CPU awake while a request is being served with the screen off.
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:request")
        wakeLock.acquire(REQUEST_WAKE_LOCK_TIMEOUT_MS)
        val remote = client.inetAddress?.hostAddress ?: "unknown"
        val startedAt = System.currentTimeMillis()
        var requestLabel = "request"
        try {
            client.soTimeout = 120_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            fun respond(status: Int, json: JSONObject) {
                writeResponse(output, status, json)
                if (status >= 400) {
                    val message = json.optJSONObject("error")?.optString("message").orEmpty()
                    log(LogKind.ERROR, "$remote · $requestLabel → $status", message)
                }
            }

            val headerBytes = readHeaders(input)
            if (headerBytes.size > 16 * 1024) {
                respond(431, errorJson("Request headers too large"))
                return
            }

            val headerText = headerBytes.toString(Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty().split(" ")
            if (requestLine.size < 2) {
                respond(400, errorJson("Malformed HTTP request"))
                return
            }

            val method = requestLine[0]
            val path = requestLine[1].substringBefore("?")
            requestLabel = "$method $path"
            val headers = mutableMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val index = line.indexOf(':')
                if (index > 0) {
                    headers[line.substring(0, index).trim().lowercase()] =
                        line.substring(index + 1).trim()
                }
            }

            if (method == "GET" && path == "/health") {
                respond(200, JSONObject()
                    .put("status", "ok")
                    .put("model", selectedModel?.id ?: JSONObject.NULL))
                return
            }

            fun readBody(): ByteArray? {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength <= 0 || contentLength > MAX_BODY_BYTES) {
                    respond(413, errorJson("Invalid or oversized request body"))
                    return null
                }
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val count = input.read(body, offset, contentLength - offset)
                    if (count < 0) break
                    offset += count
                }
                if (offset != contentLength) {
                    respond(400, errorJson("Incomplete request body"))
                    return null
                }
                return body
            }

            if (method == "POST" && path.startsWith("/v1/pair/")) {
                val body = JSONObject((readBody() ?: return).toString(Charsets.UTF_8))
                when (path) {
                    "/v1/pair/start" -> {
                        val (id, serverNonce) = Pairing.start(
                            body.optString("client_name"),
                            body.getString("commitment")
                        )
                        log(LogKind.INFO, "Pairing started by $remote", body.optString("client_name"))
                        respond(200, JSONObject()
                            .put("pairing_id", id)
                            .put("server_nonce", Pairing.toHex(serverNonce))
                            .put("device_name", deviceName()))
                    }
                    "/v1/pair/finish" -> {
                        val outcome = Pairing.finish(
                            applicationContext,
                            body.getString("pairing_id"),
                            body.getString("client_nonce"),
                            remote,
                            ::showPairingPrompt
                        )
                        cancelPairingNotification()
                        when (outcome) {
                            is Pairing.Outcome.Approved -> {
                                log(LogKind.INFO, "Paired with ${outcome.client.name}", "from $remote")
                                respond(200, JSONObject()
                                    .put("client_id", outcome.client.id)
                                    .put("token", outcome.token)
                                    .put("device_name", deviceName())
                                    .put("fingerprint", ServerIdentity.fingerprint))
                            }
                            is Pairing.Outcome.Rejected -> respond(outcome.status, errorJson(outcome.message))
                        }
                    }
                    else -> respond(404, errorJson("Endpoint not found"))
                }
                return
            }

            if (!isAuthorized(headers)) {
                respond(401, errorJson("Invalid or missing token or API key"))
                return
            }

            if (method == "GET" && path == "/v1/models") {
                val model = selectedModel
                respond(200, JSONObject()
                    .put("data", JSONArray().put(JSONObject()
                        .put("id", model?.id ?: "unknown")
                        .put("type", "model")
                        .put("display_name", model?.name ?: "Phone model")
                        .put("created_at", "2026-01-01T00:00:00Z")))
                    .put("has_more", false))
                return
            }

            if (method != "POST" || path != "/v1/messages") {
                respond(404, errorJson("Endpoint not found"))
                return
            }

            if (headers["anthropic-version"].isNullOrBlank()) {
                respond(400, errorJson("Missing anthropic-version header"))
                return
            }

            val request = JSONObject((readBody() ?: return).toString(Charsets.UTF_8))
            val response = generateMessage(request, remote)
            respond(200, response)
            val answer = response.getJSONArray("content").getJSONObject(0).getString("text")
            log(
                LogKind.RESPONSE,
                "→ $remote · 200 in %.1f s · ${answer.length} chars"
                    .format((System.currentTimeMillis() - startedAt) / 1000.0),
                answer
            )
        } catch (e: SSLException) {
            // Failed handshakes: port scans, or clients that don't speak TLS.
            Log.d(TAG, "TLS handshake with $remote failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            log(LogKind.ERROR, "$remote · $requestLabel → 500", e.message ?: e.javaClass.simpleName)
            try {
                val output = BufferedOutputStream(client.getOutputStream())
                writeResponse(output, 500, errorJson(e.message ?: "Internal server error"))
            } catch (_: Exception) {
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun readHeaders(input: BufferedInputStream): ByteArray {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)

        while (bytes.size <= 16 * 1024) {
            val value = input.read()
            if (value < 0) break
            bytes.add(value.toByte())
            if (value.toByte() == terminator[matched]) {
                matched++
                if (matched == terminator.size) break
            } else {
                matched = if (value == 13) 1 else 0
            }
        }
        return ByteArray(bytes.size) { bytes[it] }
    }

    /** A paired coordinator's token, or the API key shown in the app. */
    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val supplied = headers["x-api-key"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.takeIf {
                headers["authorization"]?.startsWith("Bearer ", ignoreCase = true) == true
            }
            ?: return false

        if (Pairing.authenticate(applicationContext, supplied) != null) return true
        val expected = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, null) ?: return false
        return constantTimeEquals(expected, supplied)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return java.security.MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    private suspend fun generateMessage(request: JSONObject, remote: String): JSONObject {
        val manager = LiteRTLMManager.getInstance(applicationContext)
        val model = selectedModel ?: throw IllegalStateException("No model selected")

        val messages = request.optJSONArray("messages")
            ?: throw IllegalArgumentException("messages must be an array")
        if (messages.length() == 0) {
            throw IllegalArgumentException("messages cannot be empty")
        }

        val parsed = mutableListOf<Pair<String, String>>()
        for (i in 0 until messages.length()) {
            val item = messages.getJSONObject(i)
            val role = item.getString("role")
            if (role != "user" && role != "assistant") {
                throw IllegalArgumentException("Only user and assistant roles are supported")
            }
            parsed.add(role to extractText(item.opt("content")))
        }

        val last = parsed.last()
        if (last.first != "user") {
            throw IllegalArgumentException("The final message must have role 'user'")
        }

        val history = parsed.dropLast(1).map { (role, text) ->
            if (role == "user") {
                Message.user(Contents.of(Content.Text(text)))
            } else {
                Message.model(text)
            }
        }

        val system = extractSystemPrompt(request.opt("system"))
        log(
            LogKind.REQUEST,
            "← $remote · ${parsed.size} message(s)" + if (system != null) " + system prompt" else "",
            last.second
        )
        val chunks = mutableListOf<String>()
        manager.sendRequestMessage(
            initialMessages = history,
            finalUserPrompt = last.second,
            systemPrompt = system
        ).collect { chunks.add(it) }

        val answer = chunks.joinToString("")
        val inputChars = parsed.sumOf { it.second.length } + (system?.length ?: 0)
        val outputChars = answer.length

        return JSONObject()
            .put("id", "msg_phone_${System.currentTimeMillis()}")
            .put("type", "message")
            .put("role", "assistant")
            .put("model", request.optString("model", model.id))
            .put("content", JSONArray().put(JSONObject()
                .put("type", "text")
                .put("text", answer)))
            .put("stop_reason", "end_turn")
            .put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject()
                .put("input_tokens", (inputChars / 4).coerceAtLeast(1))
                .put("output_tokens", (outputChars / 4).coerceAtLeast(1)))
    }

    private fun extractSystemPrompt(value: Any?): String? {
        return when (value) {
            is String -> value
            is JSONArray -> {
                val parts = mutableListOf<String>()
                for (i in 0 until value.length()) {
                    val block = value.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") {
                        parts.add(block.optString("text"))
                    }
                }
                parts.joinToString("\n").takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    private fun extractText(value: Any?): String {
        return when (value) {
            is String -> value
            is JSONArray -> {
                val parts = mutableListOf<String>()
                for (i in 0 until value.length()) {
                    val block = value.optJSONObject(i)
                        ?: throw IllegalArgumentException("Unsupported content block")
                    if (block.optString("type") != "text") {
                        throw IllegalArgumentException(
                            "Only text content blocks are supported in this version"
                        )
                    }
                    parts.add(block.optString("text"))
                }
                parts.joinToString("\n")
            }
            else -> throw IllegalArgumentException("Message content must be text")
        }
    }

    private fun errorJson(message: String): JSONObject {
        return JSONObject()
            .put("type", "error")
            .put("error", JSONObject()
                .put("type", "invalid_request_error")
                .put("message", message))
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        body: JSONObject
    ) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> "Internal Server Error"
        }
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun stopServer() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, false).apply()
        if (_status.value is Status.Stopping) return
        shutDown(Status.Stopped)
    }

    /**
     * Stops accepting connections, lets startup and in-flight requests unwind, releases
     * the model engine, then removes the notification and stops the service.
     */
    private fun shutDown(finalStatus: Status) {
        _status.value = Status.Stopping
        updateNotification("Stopping…")
        unregisterMdns()
        runCatching { serverSocket?.close() }
        serverSocket = null
        Pairing.pending.value?.let { Pairing.decide(it.id, false) }
        // Closing sockets unblocks handlers stuck reading from idle connections.
        openClients.forEach { runCatching { it.close() } }

        val running = serviceJob.children.toList()
        serviceJob.cancelChildren()
        CoroutineScope(Dispatchers.IO).launch {
            // Native model loading can't be interrupted, so wait for it before releasing.
            running.joinAll()
            LiteRTLMManager.getInstance(applicationContext).cleanup()
            withContext(Dispatchers.Main) {
                releaseWifiLock()
                _status.value = finalStatus
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "API server stopped and model released")
                if (finalStatus is Status.Stopped) log(LogKind.INFO, "Server stopped")
            }
        }
    }

    @Suppress("DEPRECATION") // HIGH_PERF is the mode that still applies with the screen off.
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:server")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "API server",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(PAIRING_CHANNEL_ID, "Pairing requests", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AnthropicServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openApp)
            .addAction(0, "Stop API", stop)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    // ---------------------------------------------------------------- mDNS

    private fun deviceName(): String = "RemoteAI on ${Build.MODEL}"

    /** Advertises the TLS port as `_remoteai._tcp` so coordinators can find the phone. */
    private fun registerMdns(model: ModelConfig) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceName()
            serviceType = MDNS_SERVICE_TYPE
            port = PORT
            setAttribute("v", "1")
            // Advisory only: coordinators trust the fingerprint they confirm while pairing.
            setAttribute("fp", ServerIdentity.fingerprint)
            setAttribute("model", model.id)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log(LogKind.INFO, "Advertising on the network as \"${info.serviceName}\"", MDNS_SERVICE_TYPE)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                log(LogKind.ERROR, "Could not advertise on the network", "NSD error $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        mdnsRegistration = listener
        getSystemService(NsdManager::class.java)
            .registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterMdns() {
        val listener = mdnsRegistration ?: return
        mdnsRegistration = null
        runCatching { getSystemService(NsdManager::class.java).unregisterService(listener) }
    }

    // ---------------------------------------------------------------- Pairing prompt

    /**
     * Shows [PairingActivity]. The full-screen intent opens it over the lock screen; when
     * the phone is in use Android shows a heads-up notification instead.
     */
    private fun showPairingPrompt(request: Pairing.Request) {
        log(LogKind.INFO, "Pairing request from ${request.clientName}", "${request.remote} · code ${request.code}")
        val intent = Intent(this, PairingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Pair with ${request.clientName}?")
            .setContentText("Code ${request.code} · tap to review")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .setTimeoutAfter(Pairing.CODE_TIMEOUT_MS)
            .build()
        getSystemService(NotificationManager::class.java).notify(PAIRING_NOTIFICATION_ID, notification)
        // Opens straight away if the app is on screen; otherwise Android ignores it.
        runCatching { startActivity(intent) }
    }

    private fun cancelPairingNotification() {
        getSystemService(NotificationManager::class.java).cancel(PAIRING_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        // Normally shutDown() has already run; this covers the system stopping us directly.
        unregisterMdns()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serviceScope.cancel()
        releaseWifiLock()
        if (_status.value !is Status.Failed) {
            _status.value = Status.Stopped
        }
        super.onDestroy()
    }
}
