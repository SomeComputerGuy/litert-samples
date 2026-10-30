package io.github.somecomputerguy.remoteairesponder

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Singleton manager for LiteRT-LM Engine, tailored for Google Tensor TPU and fallback.
 * Backend fallback chain: NPU (TPU) → GPU → CPU
 */
class LiteRTLMManager private constructor(private val context: Context) {
    
    private val inferenceMutex = Mutex()

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var isInitialized = false
    private var currentBackendName: String = "CPU"
    private var nativeRuntimeConfigured = false

    // Snapshot used to recreate the Activity conversation after API requests.
    private var activeModelPath: String? = null
    private var uiSystemPrompt: String? = null
    private val uiHistory = mutableListOf<Message>()
    
    companion object {
        private const val TAG = "LiteRTLMManager"
        
        @Volatile
        private var INSTANCE: LiteRTLMManager? = null
        
        fun getInstance(context: Context): LiteRTLMManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LiteRTLMManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Initialize the LiteRT-LM Engine with the specified model.
     * Uses NPU → GPU → CPU fallback chain.
     */
    suspend fun initialize(
        modelPath: String,
        systemPrompt: String? = null,
        preferredBackend: String? = null,
        supportsImage: Boolean = true,
        supportsAudio: Boolean = false,
        maxContext: Int = 2048,
        initialMessages: List<Message>? = null
    ): Result<Boolean> = inferenceMutex.withLock {
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Initializing model: $modelPath (preferred: $preferredBackend)")

            if (isInitialized && activeModelPath == modelPath && conversation != null) {
                Log.i(TAG, "Requested model is already initialized; reusing active engine")
                return@withContext Result.success(true)
            }

            if (isInitialized) {
                cleanupUnlocked()
            }

            try {
                val backends = buildBackendList(preferredBackend)
                initializeEngineWithFallback(
                    modelPath, backends, systemPrompt, supportsImage,
                    supportsAudio, maxContext, initialMessages
                )
                uiHistory.clear()
                uiHistory.addAll(initialMessages ?: emptyList())
                uiSystemPrompt = systemPrompt
                activeModelPath = modelPath
                isInitialized = true
                Log.i(TAG, "Initialization SUCCEEDED on backend: $currentBackendName")
                Result.success(true)
            } catch (e: Throwable) {
                Log.e(TAG, "Initialization FAILED: ${e.message}", e)
                Result.failure(Exception(e))
            }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun buildBackendList(preferred: String?): List<BackendFactory> {
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        
        val isNpuAvailable = isPackageInstalled("com.google.android.aicore")
        Log.i(TAG, "AICore package (com.google.android.aicore) installed: $isNpuAvailable")
        
        val npuBackend = BackendFactory("NPU") {
            Log.i(TAG, "Using NPU backend with nativeLibraryDir: $nativeLibraryDir")
            Backend.NPU(nativeLibraryDir = nativeLibraryDir)
        }
        val gpuBackend = BackendFactory("GPU") { Backend.GPU() }
        val cpuBackend = BackendFactory("CPU") { Backend.CPU() }
        
        val list = when (preferred?.uppercase()) {
            "NPU" -> listOf(npuBackend, gpuBackend, cpuBackend)
            "GPU" -> listOf(gpuBackend, cpuBackend)
            "CPU" -> listOf(cpuBackend, gpuBackend)
            else -> listOf(gpuBackend, cpuBackend)
        }
        
        return if (!isNpuAvailable) {
            Log.w(TAG, "NPU backend is not available on this device (AICore is missing). Filtering out NPU from fallback chain.")
            list.filter { it.name != "NPU" }
        } else {
            list
        }
    }

    private fun initializeEngineWithFallback(
        modelPath: String,
        backends: List<BackendFactory>,
        systemPrompt: String?,
        supportsImage: Boolean,
        supportsAudio: Boolean,
        maxContext: Int,
        initialMessages: List<Message>?
    ) {
        var lastError: Throwable? = null
        
        for (factory in backends) {
            try {
                Log.i(TAG, "Trying backend: ${factory.name}")
                initializeEngine(modelPath, factory, systemPrompt, supportsImage, supportsAudio, maxContext, initialMessages)
                Log.i(TAG, "Backend ${factory.name} SUCCEEDED")
                return
            } catch (e: Throwable) {
                Log.w(TAG, "Backend ${factory.name} failed: ${e.message}")
                lastError = e
            }
        }
        
        throw lastError ?: IllegalStateException("All backends failed")
    }

    private fun initializeEngine(
        modelPath: String,
        factory: BackendFactory,
        systemPrompt: String?,
        supportsImage: Boolean,
        supportsAudio: Boolean,
        maxContext: Int,
        initialMessages: List<Message>?
    ) {
        val file = File(modelPath)
        if (!file.exists()) {
            throw java.io.FileNotFoundException("Model file not found at $modelPath")
        }
        if (!file.canRead()) {
            throw java.io.IOException("Model file not readable")
        }

        if (factory.name == "NPU") {
            configureNativeRuntime(context.applicationInfo.nativeLibraryDir)
        }

        val backend = factory.create()

        val visionBackend = if (factory.name == "NPU") {
            Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        } else if (factory.name == "GPU") {
            Backend.GPU()
        } else {
            Backend.CPU()
        }

        val audioBackend = Backend.CPU()

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = visionBackend,
            audioBackend = audioBackend,
            maxNumTokens = maxContext,
            maxNumImages = 1,
            cacheDir = context.cacheDir.path
        )
        
        val startTime = System.currentTimeMillis()
        var candidateEngine = Engine(engineConfig)
        
        try {
            candidateEngine.initialize()
            val duration = System.currentTimeMillis() - startTime
            Log.i(TAG, "Engine initialization SUCCEEDED in ${duration}ms")
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Engine initialization FAILED after ${duration}ms: ${e.message}")
            try {
                candidateEngine.close()
            } catch (closeEx: Throwable) {
                Log.w(TAG, "Error closing candidate engine: ${closeEx.message}")
            }
            
            Log.i(TAG, "Retrying initialization as text-only model without vision/audio backend...")
            val textOnlyConfig = EngineConfig(
                modelPath = modelPath,
                backend = factory.create(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = maxContext,
                maxNumImages = null,
                cacheDir = context.cacheDir.path
            )
            candidateEngine = Engine(textOnlyConfig)
            try {
                candidateEngine.initialize()
                Log.i(TAG, "Text-only Engine initialization SUCCEEDED")
            } catch (retryEx: Throwable) {
                try {
                    candidateEngine.close()
                } catch (closeEx: Throwable) {
                    Log.w(TAG, "Error closing fallback candidate engine: ${closeEx.message}")
                }
                throw retryEx
            }
        }

        try {
            // Create conversation config with system prompt if provided
            val conversationConfig = ConversationConfig(
                systemInstruction = if (systemPrompt != null) Contents.of(systemPrompt) else null,
                initialMessages = initialMessages ?: emptyList()
            )
            conversation = candidateEngine.createConversation(conversationConfig)
        } catch (convEx: Throwable) {
            try {
                candidateEngine.close()
            } catch (closeEx: Throwable) {
                Log.w(TAG, "Error closing candidate engine after conversation failure: ${closeEx.message}")
            }
            throw convEx
        }

        engine = candidateEngine
        currentBackendName = factory.name
    }

    @Synchronized
    private fun configureNativeRuntime(nativeLibraryDir: String) {
        if (nativeRuntimeConfigured) {
            return
        }

        try {
            System.loadLibrary("LiteRtDispatch_GoogleTensor")
            Log.i(TAG, "Successfully loaded libLiteRtDispatch_GoogleTensor.so via System.loadLibrary")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load LiteRtDispatch_GoogleTensor: ${e.message}")
        }

        try {
            val ldPath = android.system.Os.getenv("LD_LIBRARY_PATH") ?: ""
            val newLdPath = if (ldPath.isEmpty()) nativeLibraryDir else "$nativeLibraryDir:$ldPath"
            android.system.Os.setenv("LD_LIBRARY_PATH", newLdPath, true)
            Log.i(TAG, "Set LD_LIBRARY_PATH to $newLdPath")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set LD_LIBRARY_PATH: ${e.message}")
        }

        nativeRuntimeConfigured = true
    }
    /**
     * Send a text-only message and stream the response.
     * The lock remains held until collection of the response completes.
     */
    fun sendMessage(text: String): Flow<String> = flow {
        inferenceMutex.withLock {
            val conv = conversation
                ?: throw IllegalStateException("Engine/Conversation not initialized.")

            val response = StringBuilder()
            conv.sendMessageAsync(text).collect { msg ->
                val chunk = msg.extractText()
                response.append(chunk)
                emit(chunk)
            }

            uiHistory.add(Message.user(text))
            if (response.isNotEmpty()) {
                uiHistory.add(Message.model(response.toString()))
            }
        }
    }

    /**
     * Send a multimodal message with optional image and/or audio files.
     */
    /**
     * Runs an isolated API request conversation on the currently loaded engine.
     * The Activity's conversation and chat history are not modified.
     * initialMessages contains the request history before the final user prompt.
     */
    fun sendRequestMessage(
        initialMessages: List<Message>,
        finalUserPrompt: String,
        systemPrompt: String? = null
    ): Flow<String> = flow {
        inferenceMutex.withLock {
            val activeEngine = engine
                ?: throw IllegalStateException("Engine is not initialized.")

            if (!isInitialized) {
                throw IllegalStateException("Engine is not initialized.")
            }

            // LiteRT-LM supports one session per engine. Close the UI session
            // before creating the independent API request session.
            conversation?.close()
            conversation = null

            try {
                val requestConversation = activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = if (systemPrompt != null) {
                            Contents.of(systemPrompt)
                        } else {
                            null
                        },
                        initialMessages = initialMessages
                    )
                )

                try {
                    requestConversation.sendMessageAsync(finalUserPrompt).collect { msg ->
                        emit(msg.extractText())
                    }
                } finally {
                    requestConversation.close()
                }
            } finally {
                // Restore the Activity session regardless of API success/failure.
                conversation = activeEngine.createConversation(
                    ConversationConfig(
                        systemInstruction = if (uiSystemPrompt != null) {
                            Contents.of(uiSystemPrompt!!)
                        } else {
                            null
                        },
                        initialMessages = uiHistory.toList()
                    )
                )
                Log.i(TAG, "Restored Activity conversation after API request")
            }
        }
    }

    fun sendMultimodalMessage(
        text: String,
        imagePath: String? = null,
        audioPath: String? = null
    ): Flow<String> = flow {
        inferenceMutex.withLock {
            val conv = conversation
                ?: throw IllegalStateException("Engine/Conversation not initialized.")

            val contentParts = mutableListOf<Content>()

            if (imagePath != null) {
                contentParts.add(Content.ImageFile(imagePath))
            }

            if (audioPath != null) {
                contentParts.add(Content.AudioFile(audioPath))
            }

            contentParts.add(Content.Text(text))
            val contents = Contents.of(*contentParts.toTypedArray())

            val response = StringBuilder()
            conv.sendMessageAsync(contents).collect { msg ->
                val chunk = msg.extractText()
                response.append(chunk)
                emit(chunk)
            }

            uiHistory.add(Message.user(contents))
            if (response.isNotEmpty()) {
                uiHistory.add(Message.model(response.toString()))
            }
        }
    }

    private fun Message.extractText(): String {
        return contents.contents.joinToString(separator = "") { content ->
            when (content) {
                is Content.Text -> content.text
                else -> ""
            }
        }
    }

    fun getActiveBackendName(): String = currentBackendName

    fun cleanup() {
        runBlocking {
            inferenceMutex.withLock {
                withContext(Dispatchers.IO) {
                    cleanupUnlocked()
                }
            }
        }
    }

    // Caller must already hold inferenceMutex.
    private fun cleanupUnlocked() {
        try {
            conversation?.close()
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            conversation = null
            engine = null
            isInitialized = false
            activeModelPath = null
            uiSystemPrompt = null
            uiHistory.clear()
        }
    }
}

private data class BackendFactory(
    val name: String,
    val create: () -> Backend
)
