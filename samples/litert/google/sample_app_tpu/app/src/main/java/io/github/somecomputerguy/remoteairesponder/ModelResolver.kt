package io.github.somecomputerguy.remoteairesponder

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ModelResolver(private val context: Context) {

    companion object {
        private const val TAG = "ModelResolver"

        val GEMMA3_CPU = ModelConfig(
            id = "gemma3-cpu",
            name = "Gemma 3 (Assets - CPU)",
            filename = "gemma3_cpu.litertlm",
            isAsset = true,
            systemPrompt = "You are a helpful assistant.",
            preferredBackend = "CPU",
            supportsImage = false,
            supportsAudio = false,
            defaultPrompt = "What is this?",
            maxContext = 2048
        )

        val TINY_GARDEN = ModelConfig(
            id = "tiny-garden",
            name = "Tiny Garden (Assets - CPU)",
            filename = "tiny_garden.litertlm",
            isAsset = true,
            systemPrompt = "You are a helpful assistant.",
            preferredBackend = "CPU",
            supportsImage = false,
            supportsAudio = false,
            defaultPrompt = "Analyze the image.",
            maxContext = 2048
        )

        private const val GEMMA4_E2B_BASE_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/"

        val GEMMA4_TENSOR_G5 = ModelConfig(
            id = "gemma4-e2b-tensor-g5",
            name = "Gemma 4 E2B (Download - Tensor G5)",
            filename = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
            isAsset = false,
            systemPrompt = "You are a helpful assistant.",
            preferredBackend = "NPU",
            supportsImage = false,
            supportsAudio = false,
            defaultPrompt = "What is this?",
            maxContext = 2048,
            downloadUrl = GEMMA4_E2B_BASE_URL + "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
            sizeBytes = 3_113_545_589L
        )

        val GEMMA4_TENSOR_G6 = GEMMA4_TENSOR_G5.copy(
            id = "gemma4-e2b-tensor-g6",
            name = "Gemma 4 E2B (Download - Tensor G6)",
            filename = "gemma-4-E2B-it_Google_Tensor_G6.litertlm",
            downloadUrl = GEMMA4_E2B_BASE_URL + "gemma-4-E2B-it_Google_Tensor_G6.litertlm",
            sizeBytes = 3_313_938_293L
        )

        // Fallback for chips without a TPU build (e.g. Tensor G4); untested on Pixel.
        val GEMMA4_GPU = GEMMA4_TENSOR_G5.copy(
            id = "gemma4-e2b-gpu",
            name = "Gemma 4 E2B (Download - GPU)",
            filename = "gemma-4-E2B-it-gpu.litertlm",
            preferredBackend = "GPU",
            downloadUrl = GEMMA4_E2B_BASE_URL + "gemma-4-E2B-it-gpu.litertlm",
            sizeBytes = 2_008_432_640L
        )

        // Larger, better-quality GPU option for high-end phones without a TPU build.
        val GEMMA4_E4B_GPU = GEMMA4_GPU.copy(
            id = "gemma4-e4b-gpu",
            name = "Gemma 4 E4B (Download - GPU)",
            filename = "gemma-4-E4B-it-gpu.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/" +
                "gemma-4-E4B-it-gpu.litertlm",
            sizeBytes = 2_969_059_328L
        )

        private const val E4B_MIN_RAM_BYTES = 11L shl 30 // "12 GB" phones report a little under 12 GiB

        /**
         * Downloadable models for this phone, most suitable first: its Tensor TPU build if
         * one exists, otherwise the GPU builds (E4B only with enough RAM).
         */
        fun offeredModels(context: Context): List<ModelConfig> {
            when (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null) {
                "Tensor G5" -> return listOf(GEMMA4_TENSOR_G5)
                "Tensor G6" -> return listOf(GEMMA4_TENSOR_G6)
            }
            val memory = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
            return if (memory.totalMem >= E4B_MIN_RAM_BYTES) {
                listOf(GEMMA4_GPU, GEMMA4_E4B_GPU)
            } else {
                listOf(GEMMA4_GPU)
            }
        }

        // Starts empty: the sample's bundled asset models aren't shipped in this build.
        // Holds offered models once the user accepts them, plus sideloaded models.
        val AVAILABLE_MODELS = mutableListOf<ModelConfig>()

        private const val PREFS_NAME = "ModelResolverPrefs"
        private const val KEY_CUSTOM_MODELS = "CustomModels"
        private const val KEY_ACCEPTED_DOWNLOADS = "AcceptedDownloads"
        // Earlier test builds stored a single flag for the one offered model.
        private const val KEY_LEGACY_DEVICE_MODEL_ACCEPTED = "DeviceModelAccepted"

        private fun acceptedDownloads(context: Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ids = prefs.getStringSet(KEY_ACCEPTED_DOWNLOADS, null).orEmpty()
            if (!prefs.getBoolean(KEY_LEGACY_DEVICE_MODEL_ACCEPTED, false)) return ids
            val migrated = ids + offeredModels(context).first().id
            prefs.edit()
                .putStringSet(KEY_ACCEPTED_DOWNLOADS, migrated)
                .remove(KEY_LEGACY_DEVICE_MODEL_ACCEPTED)
                .apply()
            return migrated
        }

        private fun setAccepted(context: Context, model: ModelConfig, accepted: Boolean) {
            val ids = acceptedDownloads(context).toMutableSet()
            if (accepted) ids.add(model.id) else ids.remove(model.id)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_ACCEPTED_DOWNLOADS, ids).apply()
        }

        /** Lists an offered model from now on; the caller starts its download. */
        fun acceptDownload(context: Context, model: ModelConfig) {
            setAccepted(context, model, true)
            if (AVAILABLE_MODELS.none { it.id == model.id }) {
                AVAILABLE_MODELS.add(AVAILABLE_MODELS.count { it.downloadUrl != null }, model)
            }
        }

        fun loadCustomModels(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val accepted = acceptedDownloads(context)
            offeredModels(context)
                .filter { it.id in accepted && AVAILABLE_MODELS.none { m -> m.id == it.id } }
                .forEach { AVAILABLE_MODELS.add(AVAILABLE_MODELS.count { m -> m.downloadUrl != null }, it) }
            val json = prefs.getString(KEY_CUSTOM_MODELS, null)
            if (json != null) {
                try {
                    val array = org.json.JSONArray(json)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val config = ModelConfig(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            filename = obj.getString("filename"),
                            isAsset = obj.getBoolean("isAsset"),
                            systemPrompt = if (obj.has("systemPrompt")) obj.getString("systemPrompt") else null,
                            preferredBackend = if (obj.has("preferredBackend")) obj.getString("preferredBackend") else null,
                            supportsImage = obj.getBoolean("supportsImage"),
                            supportsAudio = obj.getBoolean("supportsAudio"),
                            defaultPrompt = obj.getString("defaultPrompt"),
                            maxContext = if (obj.has("maxContext")) obj.getInt("maxContext") else 2048
                        )
                        if (AVAILABLE_MODELS.none { it.id == config.id }) {
                            AVAILABLE_MODELS.add(config)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun saveCustomModel(context: Context, config: ModelConfig) {
            if (AVAILABLE_MODELS.none { it.id == config.id }) {
                AVAILABLE_MODELS.add(config)
            }
            saveCustomModels(context)
        }

        private fun saveCustomModels(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val customModels = AVAILABLE_MODELS.filter { !it.isAsset && it.downloadUrl == null }
            val array = org.json.JSONArray()
            for (model in customModels) {
                val obj = org.json.JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("filename", model.filename)
                    put("isAsset", model.isAsset)
                    put("systemPrompt", model.systemPrompt ?: "")
                    put("preferredBackend", model.preferredBackend ?: "")
                    put("supportsImage", model.supportsImage)
                    put("supportsAudio", model.supportsAudio)
                    put("defaultPrompt", model.defaultPrompt)
                    put("maxContext", model.maxContext)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_CUSTOM_MODELS, array.toString()).apply()
        }

        fun deleteCustomModel(context: Context, config: ModelConfig) {
            AVAILABLE_MODELS.removeAll { it.id == config.id }

            if (config.downloadUrl != null) {
                // Unlist it; the "Download" entry brings it back.
                setAccepted(context, config, false)
                ModelDownloader.cancel(config.id)
            } else {
                // Remove from preferences by re-saving the remaining custom models.
                saveCustomModels(context)
            }

            // Free the space: every copy this app may hold of the file, not just the
            // one this entry was loaded from.
            if (!config.isAsset) {
                val resolver = ModelResolver(context)
                val downloaded = resolver.downloadedModelFile(config)
                val externalDir = context.getExternalFilesDir(null)
                listOfNotNull(
                    downloaded,
                    File(downloaded.path + ".part"),
                    File(context.cacheDir, config.filename),
                    externalDir?.let { File(it, config.filename) }
                ).filter { it.exists() }.forEach { file ->
                    val size = file.length()
                    if (file.delete()) {
                        Log.i(TAG, "Deleted ${file.absolutePath} (${size / 1_000_000} MB)")
                    } else {
                        Log.w(TAG, "Could not delete ${file.absolutePath}")
                    }
                }
            }
        }
    }

    /**
     * Resolves the final local absolute path for the model.
     * For assets, it copies the file to context.cacheDir if it does not exist.
     * For external models, it references the external files directory.
     */
    fun resolveModelPath(modelConfig: ModelConfig): String {
        if (modelConfig.downloadUrl != null) {
            return (findDownloadedModel(modelConfig) ?: downloadedModelFile(modelConfig)).absolutePath
        }
        if (modelConfig.isAsset) {
            val cacheFile = File(context.cacheDir, modelConfig.filename)
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                try {
                    Log.i(TAG, "Copying asset ${modelConfig.filename} to cache...")
                    context.assets.open(modelConfig.filename).use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Asset copy complete: ${cacheFile.length()} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "Asset ${modelConfig.filename} not found or copy failed", e)
                }
            }
            return cacheFile.absolutePath
        } else {
            // Prioritize internal cache directory to bypass SELinux boundaries for TPU/NPU HAL
            val cacheFile = File(context.cacheDir, modelConfig.filename)
            val externalDir = context.getExternalFilesDir(null)
                ?: File("/sdcard/Android/data/${context.packageName}/files")
            val externalFile = File(externalDir, modelConfig.filename)
            
            if (cacheFile.exists() && cacheFile.length() == externalFile.length()) {
                Log.d(TAG, "Using model from internal cache: ${cacheFile.absolutePath}")
                return cacheFile.absolutePath
            }
            
            if (externalFile.exists()) {
                try {
                    Log.i(TAG, "Copying external model ${modelConfig.filename} to cache to bypass SELinux...")
                    externalFile.inputStream().use { input ->
                        FileOutputStream(cacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "External model copy complete: ${cacheFile.length()} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy external model ${modelConfig.filename}", e)
                }
            }
            
            return cacheFile.absolutePath
        }
    }

    /**
     * Checks if the model is ready to be loaded.
     * Assets are always checked against context.assets or cacheDir.
     * External files must exist, be readable, and have non-zero length.
     */
    fun isModelAvailable(modelConfig: ModelConfig): Boolean {
        if (modelConfig.downloadUrl != null) {
            return findDownloadedModel(modelConfig) != null
        }
        val cacheFile = File(context.cacheDir, modelConfig.filename)
        if (cacheFile.exists() && cacheFile.canRead() && cacheFile.length() > 0) {
            return true
        }
        if (modelConfig.isAsset) {
            return true
        } else {
            val externalDir = context.getExternalFilesDir(null)
                ?: File("/sdcard/Android/data/${context.packageName}/files")
            val modelFile = File(externalDir, modelConfig.filename)
            val available = modelFile.exists() && modelFile.canRead() && modelFile.length() > 0
            Log.d(TAG, "External model ${modelConfig.filename} availability check: $available at ${modelFile.absolutePath}")
            return available
        }
    }

    /** Where the app downloads models that have a [ModelConfig.downloadUrl]. */
    fun downloadedModelFile(modelConfig: ModelConfig): File =
        File(File(context.filesDir, "models"), modelConfig.filename)

    /**
     * Returns a complete copy of a downloadable model, also accepting one left in
     * cacheDir by an earlier sideload of the same file so it isn't fetched twice.
     */
    private fun findDownloadedModel(modelConfig: ModelConfig): File? =
        listOf(downloadedModelFile(modelConfig), File(context.cacheDir, modelConfig.filename))
            .firstOrNull { it.canRead() && it.length() == modelConfig.sizeBytes }
}
