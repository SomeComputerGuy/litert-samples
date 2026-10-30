package com.google.googletensortpu.googleTensorTPUApp

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Control panel for using the phone as a remote model server: pick (or download) a model,
 * start and stop the Anthropic-compatible API, copy its connection details, and watch the
 * traffic it serves. The API service owns the model engine; this screen never loads it.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var spinnerModel: Spinner
    private lateinit var textBackendStatus: TextView
    private lateinit var textModelStatus: TextView
    private lateinit var progressDownload: LinearProgressIndicator
    private lateinit var logRecyclerView: RecyclerView
    private lateinit var textEmptyLog: TextView
    private lateinit var textBaseUrl: TextView
    private lateinit var textApiKey: TextView
    private lateinit var buttonApiServer: MaterialButton

    private lateinit var modelResolver: ModelResolver
    private val logAdapter = LogAdapter()

    private var selectedModel: ModelConfig? = null
    private var downloadJob: Job? = null

    // Server start waiting on the notification permission prompt.
    private var pendingServerModel: ModelConfig? = null

    private val serverPrefs by lazy {
        getSharedPreferences(AnthropicServerService.PREFS, MODE_PRIVATE)
    }

    private val uiPrefs by lazy {
        getSharedPreferences("MainActivityPrefs", MODE_PRIVATE)
    }

    private val pickModelFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showUploadModelDialog(uri)
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // The server runs either way; without the permission its notification is hidden.
            pendingServerModel?.let {
                pendingServerModel = null
                startServer(it)
            }
        }

    private fun isServerActive(): Boolean = AnthropicServerService.isActive

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerModel = findViewById(R.id.spinnerModel)
        textBackendStatus = findViewById(R.id.textBackendStatus)
        textModelStatus = findViewById(R.id.textModelStatus)
        progressDownload = findViewById(R.id.progressDownload)
        logRecyclerView = findViewById(R.id.logRecyclerView)
        textEmptyLog = findViewById(R.id.textEmptyLog)
        textBaseUrl = findViewById(R.id.textBaseUrl)
        textApiKey = findViewById(R.id.textApiKey)
        buttonApiServer = findViewById(R.id.buttonApiServer)

        ModelResolver.loadCustomModels(this)
        modelResolver = ModelResolver(this)
        selectedModel = ModelResolver.AVAILABLE_MODELS.find {
            it.id == serverPrefs.getString(AnthropicServerService.KEY_MODEL_ID, null)
        }

        setupModelSelector()
        setupLog()
        setupConnectionPanel()
        applyWindowInsets()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AnthropicServerService.status.collect { onServerStatus(it) }
            }
        }

        if (!isServerActive() && AnthropicServerService.wasLeftOn(this)) {
            // The API was on when the process died (e.g. an app update), so bring it back.
            val model = selectedModel
            if (model != null && modelResolver.isModelAvailable(model)) {
                startServer(model, showDetails = false)
            } else {
                serverPrefs.edit().putBoolean(AnthropicServerService.KEY_ENABLED, false).apply()
            }
        }

        showModelState()

        // Suggest the most suitable model once; the rest stay in the model menu.
        val suggested = ModelResolver.offeredModels(this).first()
        if (savedInstanceState == null && !isServerActive() && suggested in pendingDownloads() &&
            !uiPrefs.getBoolean("device_model_prompted", false)
        ) {
            uiPrefs.edit().putBoolean("device_model_prompted", true).apply()
            promptModelDownload(suggested)
        }
    }

    override fun onResume() {
        super.onResume()
        // The phone's address can change while the app is in the background.
        textBaseUrl.text = baseUrl()
    }

    private fun applyWindowInsets() {
        val headerBar = findViewById<View>(R.id.headerBar)
        val topPadding = headerBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(headerBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, topPadding + bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        val bottomBar = findViewById<View>(R.id.bottomBar)
        val bottomPadding = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding + bars.bottom)
            insets
        }
    }

    // ---------------------------------------------------------------- Model selection

    inner class ModelSpinnerAdapter(context: Context) :
        ArrayAdapter<String>(context, R.layout.item_spinner_model, android.R.id.text1, mutableListOf()) {

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_spinner_dropdown_custom, parent, false)
            view.findViewById<TextView>(android.R.id.text1).text = getItem(position)
            val deleteIcon = view.findViewById<ImageView>(R.id.deleteIcon)

            val model = ModelResolver.AVAILABLE_MODELS.getOrNull(position - 1)
            if (position > 0 && model != null && !model.isAsset) {
                deleteIcon.visibility = View.VISIBLE
                deleteIcon.setOnClickListener { confirmDeleteModel(model) }
            } else {
                deleteIcon.visibility = View.GONE
                deleteIcon.setOnClickListener(null)
            }
            return view
        }
    }

    /** Models offered for this phone that aren't listed yet, shown as "Download ..." entries. */
    private fun pendingDownloads(): List<ModelConfig> =
        ModelResolver.offeredModels(this).filter { offered ->
            ModelResolver.AVAILABLE_MODELS.none { it.id == offered.id }
        }

    private fun selectedIndex(): Int =
        selectedModel?.let { ModelResolver.AVAILABLE_MODELS.indexOf(it) + 1 } ?: 0

    private fun refreshSpinner() {
        val adapter = spinnerModel.adapter as ModelSpinnerAdapter
        adapter.clear()
        adapter.add("Select a model...")
        adapter.addAll(ModelResolver.AVAILABLE_MODELS.map { it.name })
        adapter.addAll(pendingDownloads().map { "Download ${it.name}..." })
        adapter.add("Upload New Model...")
        adapter.notifyDataSetChanged()
        spinnerModel.setSelection(selectedIndex().coerceAtLeast(0), false)
    }

    private fun setupModelSelector() {
        val adapter = ModelSpinnerAdapter(this)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown_custom)
        spinnerModel.adapter = adapter
        refreshSpinner()

        spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == selectedIndex()) return
                // Menu actions aren't real selections; put the spinner back where it was.
                spinnerModel.setSelection(selectedIndex(), false)

                val listed = ModelResolver.AVAILABLE_MODELS.size
                when {
                    position == 0 -> Unit
                    position == spinnerModel.adapter.count - 1 -> pickModelFile.launch(arrayOf("*/*"))
                    position > listed -> promptModelDownload(pendingDownloads()[position - listed - 1])
                    else -> selectModel(ModelResolver.AVAILABLE_MODELS[position - 1])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun selectModel(model: ModelConfig) {
        if (isServerActive()) {
            Toast.makeText(this, "Stop the API before switching models", Toast.LENGTH_SHORT).show()
            return
        }
        downloadJob?.cancel()
        selectedModel = model
        serverPrefs.edit().putString(AnthropicServerService.KEY_MODEL_ID, model.id).apply()
        spinnerModel.setSelection(selectedIndex(), false)
        showModelState()
    }

    /** Shows whether the selected model is ready, and downloads it if it needs fetching. */
    private fun showModelState() {
        progressDownload.visibility = View.GONE
        val model = selectedModel
        textModelStatus.text = when {
            model == null -> "Select a model to begin"
            modelResolver.isModelAvailable(model) -> "Ready · prefers ${model.preferredBackend ?: "GPU"}"
            model.downloadUrl != null -> {
                downloadModel(model)
                "Downloading…"
            }
            else -> "Model file missing. Delete it and upload it again."
        }
        updateApiButton(AnthropicServerService.status.value)
    }

    private fun confirmDeleteModel(model: ModelConfig) {
        // Close the dropdown first.
        try {
            val method = Spinner::class.java.getDeclaredMethod("onDetachedFromWindow")
            method.isAccessible = true
            method.invoke(spinnerModel)
        } catch (e: Exception) {
            Log.w(TAG, "Could not close model dropdown", e)
        }
        if (isServerActive()) {
            Toast.makeText(this, "Stop the API before deleting models", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete ${model.name}?")
            .setMessage("This removes the model file from the phone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                ModelResolver.deleteCustomModel(this, model)
                if (selectedModel?.id == model.id) {
                    downloadJob?.cancel()
                    selectedModel = null
                    serverPrefs.edit().remove(AnthropicServerService.KEY_MODEL_ID).apply()
                }
                refreshSpinner()
                showModelState()
            }
            .show()
    }

    // ---------------------------------------------------------------- Downloads

    private fun promptModelDownload(model: ModelConfig) {
        AlertDialog.Builder(this)
            .setTitle("Download a model for this phone?")
            .setMessage(
                when {
                    model.preferredBackend == "NPU" ->
                        "${model.name} is compiled for this phone's TPU.\n\n"
                    model.id == ModelResolver.GEMMA4_E4B_GPU.id ->
                        "${model.name} runs on the GPU. It is larger and about half as fast as E2B, " +
                        "but gives better answers.\n\n"
                    else ->
                        "There's no TPU build for this phone's chip, so ${model.name} runs on the GPU.\n\n"
                } +
                "Download it from Hugging Face (%.1f GB)? Wi-Fi is recommended; ".format(model.sizeBytes / 1e9) +
                "an interrupted download resumes where it stopped.\n\n" +
                "You can also do this later from the model menu, or upload your own model there."
            )
            .setPositiveButton("Download") { _, _ ->
                if (isServerActive()) {
                    Toast.makeText(this, "Stop the API before switching models", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ModelResolver.acceptDownload(this, model)
                refreshSpinner()
                selectModel(model)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun downloadModel(model: ModelConfig) {
        progressDownload.visibility = View.VISIBLE
        progressDownload.isIndeterminate = true

        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            val result = ModelDownloader.download(this@MainActivity, model)
                .onEach { state ->
                    if (state is ModelDownloader.State.Running) {
                        progressDownload.isIndeterminate = false
                        progressDownload.progress = (state.downloadedBytes * 1000 / state.totalBytes).toInt()
                        textModelStatus.text = buildString {
                            append("Downloading · %.2f of %.2f GB".format(
                                state.downloadedBytes / 1e9, state.totalBytes / 1e9
                            ))
                            if (state.bytesPerSecond > 0) {
                                append(" · %.1f MB/s".format(state.bytesPerSecond / 1e6))
                            }
                            state.secondsLeft?.let { append(" · ${formatDuration(it)} left") }
                        }
                    }
                }
                .first { it is ModelDownloader.State.Done || it is ModelDownloader.State.Failed }

            if (selectedModel?.id != model.id) return@launch
            if (result is ModelDownloader.State.Failed) {
                progressDownload.visibility = View.GONE
                textModelStatus.text = "Download failed"
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download failed")
                    .setMessage("${result.message}\n\nRetry? The download resumes where it stopped.")
                    .setPositiveButton("Retry") { _, _ -> showModelState() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                showModelState()
            }
        }
    }

    private fun formatDuration(seconds: Long): String = when {
        seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }

    // ---------------------------------------------------------------- Custom model upload

    private fun showUploadModelDialog(uri: Uri) {
        val filename = getFileName(uri) ?: "custom_model.litertlm"

        val dialogView = layoutInflater.inflate(R.layout.dialog_upload_model, null)
        val tvFileName = dialogView.findViewById<TextView>(R.id.dialogModelFileName)
        val etSystemPrompt = dialogView.findViewById<EditText>(R.id.dialogSystemPrompt)
        val etDefaultPrompt = dialogView.findViewById<EditText>(R.id.dialogDefaultPrompt)
        val spinnerBackend = dialogView.findViewById<Spinner>(R.id.dialogPreferredBackend)
        val etMaxContext = dialogView.findViewById<EditText>(R.id.dialogMaxContext)
        val cbImage = dialogView.findViewById<CheckBox>(R.id.dialogSupportsImage)
        val cbAudio = dialogView.findViewById<CheckBox>(R.id.dialogSupportsAudio)

        tvFileName.text = "File: $filename"
        etSystemPrompt.setText("You are a helpful assistant.")
        etDefaultPrompt.setText("help answer based on the provided context")
        etMaxContext.setText("2048")
        spinnerBackend.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, arrayOf("NPU", "GPU", "CPU")
        )

        AlertDialog.Builder(this)
            .setTitle("Upload Model")
            .setView(dialogView)
            .setPositiveButton("Add Model") { _, _ ->
                val modelConfig = ModelConfig(
                    id = "custom-${System.currentTimeMillis()}",
                    name = filename,
                    filename = filename,
                    isAsset = false,
                    systemPrompt = etSystemPrompt.text.toString(),
                    preferredBackend = spinnerBackend.selectedItem.toString(),
                    supportsImage = cbImage.isChecked,
                    supportsAudio = cbAudio.isChecked,
                    defaultPrompt = etDefaultPrompt.text.toString(),
                    maxContext = etMaxContext.text.toString().toIntOrNull() ?: 2048
                )
                copyModelAndAdd(uri, modelConfig)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) return it.getString(index)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun copyModelAndAdd(uri: Uri, modelConfig: ModelConfig) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Copying Model")
            .setMessage("Please wait while the model is being copied...")
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val externalDir = getExternalFilesDir(null) ?: File("/sdcard/Android/data/${packageName}/files")
                externalDir.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(File(externalDir, modelConfig.filename)).use { output ->
                        input.copyTo(output)
                    }
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    ModelResolver.saveCustomModel(this@MainActivity, modelConfig)
                    refreshSpinner()
                    selectModel(modelConfig)
                    Toast.makeText(this@MainActivity, "Model added", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy model file", e)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Failed to copy model", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------------------------------------------------------- Traffic log

    private fun setupLog() {
        logRecyclerView.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        logRecyclerView.adapter = logAdapter
        findViewById<MaterialButton>(R.id.buttonClearLog).setOnClickListener {
            AnthropicServerService.clearLog()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AnthropicServerService.log.collect { entries ->
                    // Follow new entries only if the user hasn't scrolled up to read older ones.
                    val atBottom = !logRecyclerView.canScrollVertically(1)
                    logAdapter.submit(entries)
                    textEmptyLog.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    if (atBottom && entries.isNotEmpty()) {
                        logRecyclerView.scrollToPosition(entries.size - 1)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Connection + API switch

    private fun getOrCreateApiToken(): String {
        val existing = serverPrefs.getString(AnthropicServerService.KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        serverPrefs.edit().putString(AnthropicServerService.KEY_TOKEN, token).apply()
        return token
    }

    private fun getPhoneIpAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun baseUrl(): String =
        "http://${getPhoneIpAddress() ?: "PHONE_IP"}:${AnthropicServerService.PORT}"

    private fun copyToClipboard(label: String, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            // Keeps the key out of the clipboard preview on Android 13+.
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ shows its own confirmation.
            Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupConnectionPanel() {
        val token = getOrCreateApiToken()
        textApiKey.text = "${token.take(6)}…${token.takeLast(4)}"
        textBaseUrl.text = baseUrl()

        findViewById<MaterialButton>(R.id.buttonCopyKey).setOnClickListener {
            copyToClipboard("API key", getOrCreateApiToken(), sensitive = true)
        }
        findViewById<MaterialButton>(R.id.buttonCopyUrl).setOnClickListener {
            textBaseUrl.text = baseUrl()
            copyToClipboard("Base URL", baseUrl(), sensitive = false)
        }

        buttonApiServer.setOnClickListener {
            if (isServerActive()) {
                AlertDialog.Builder(this)
                    .setTitle("Stop the API?")
                    .setMessage("Remote clients will stop getting responses and the model is unloaded.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Stop") { _, _ -> AnthropicServerService.stop(this) }
                    .show()
                return@setOnClickListener
            }

            val model = selectedModel
            if (model == null) {
                Toast.makeText(this, "Select a model first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!modelResolver.isModelAvailable(model)) {
                Toast.makeText(this, "${model.name} isn't downloaded yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingServerModel = model
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startServer(model)
            }
        }
    }

    private fun startServer(model: ModelConfig, showDetails: Boolean = true) {
        getOrCreateApiToken()
        try {
            AnthropicServerService.start(this, model)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start the API: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (showDetails) requestBatteryExemptionOnce()
    }

    /** Asks once to exempt the app from battery optimization so the API keeps serving with the screen off. */
    private fun requestBatteryExemptionOnce() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName) ||
            uiPrefs.getBoolean("battery_exemption_asked", false)
        ) return
        uiPrefs.edit().putBoolean("battery_exemption_asked", true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Log.w(TAG, "Battery optimization request unavailable", e)
        }
    }

    private fun onServerStatus(status: AnthropicServerService.Status) {
        updateApiButton(status)
        textBackendStatus.text = when (status) {
            is AnthropicServerService.Status.Running ->
                LiteRTLMManager.getInstance(this).getActiveBackendName()
            else -> "--"
        }
        textBackendStatus.backgroundTintList = ColorStateList.valueOf(
            when (textBackendStatus.text) {
                "NPU" -> 0xFF10B981.toInt()
                "GPU" -> 0xFF3B82F6.toInt()
                "CPU" -> 0xFFEF4444.toInt()
                else -> 0xFF2E2E38.toInt()
            }
        )
        spinnerModel.isEnabled = !isServerActive()
        if (status is AnthropicServerService.Status.Running) textBaseUrl.text = baseUrl()
    }

    private fun updateApiButton(status: AnthropicServerService.Status) {
        val (label, color) = when (status) {
            is AnthropicServerService.Status.Running -> "Stop API" to 0xFFEF4444.toInt()
            is AnthropicServerService.Status.Starting -> "Starting… (tap to cancel)" to 0xFF4B5563.toInt()
            AnthropicServerService.Status.Stopping -> "Stopping…" to 0xFF4B5563.toInt()
            else -> "Start API" to 0xFF10B981.toInt()
        }
        buttonApiServer.text = label
        buttonApiServer.backgroundTintList = ColorStateList.valueOf(color)
        val modelReady = selectedModel?.let { modelResolver.isModelAvailable(it) } == true
        buttonApiServer.isEnabled = when (status) {
            AnthropicServerService.Status.Stopping -> false
            is AnthropicServerService.Status.Running, is AnthropicServerService.Status.Starting -> true
            else -> modelReady
        }
        buttonApiServer.alpha = if (buttonApiServer.isEnabled) 1f else 0.5f
    }
}
