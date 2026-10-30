package com.google.googletensortpu.googleTensorTPUApp

import android.content.Context
import android.os.Bundle
import android.widget.Spinner
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import org.junit.Assert.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Reset AVAILABLE_MODELS to initial state
        ModelResolver.AVAILABLE_MODELS.clear()
        ModelResolver.AVAILABLE_MODELS.add(ModelResolver.GEMMA3_CPU)
        ModelResolver.AVAILABLE_MODELS.add(ModelResolver.TINY_GARDEN)
        
        val prefs = context.getSharedPreferences("ModelResolverPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun testActivityRecreationRestoresCustomModel() {
        // 1. Create a custom model and save it
        val customModel = ModelConfig(
            id = "my-custom-model",
            name = "My Custom Model",
            filename = "my_custom.litertlm",
            isAsset = false
        )
        ModelResolver.saveCustomModel(context, customModel)

        // 2. Launch the activity using Robolectric
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create()
        controller.start()
        controller.resume()
        
        val activity = controller.get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        
        // Let's programmatically select the custom model in the spinner/selection
        val spinner: Spinner = activity.findViewById(R.id.spinnerModel)
        
        // Make sure custom model exists in the adapter
        val adapter = spinner.adapter
        assertNotNull(adapter)
        
        // Trigger listener manually
        val listener = spinner.onItemSelectedListener
        assertNotNull(listener)
        // First call to disable isInitializing
        listener!!.onItemSelected(spinner, null, 0, 0L)
        // Second call to select the custom model
        val customModelIndex = ModelResolver.AVAILABLE_MODELS.indexOfFirst { it.id == "my-custom-model" } + 1
        listener.onItemSelected(spinner, null, customModelIndex, 0L)
        
        val initialSelectedModelField = MainActivity::class.java.getDeclaredField("selectedModel")
        initialSelectedModelField.isAccessible = true
        val initialSelectedModel = initialSelectedModelField.get(activity) as? ModelConfig
        assertNotNull("selectedModel should be set on initial activity before saving state", initialSelectedModel)
        assertEquals("my-custom-model", initialSelectedModel?.id)
        
        // Save instance state
        val savedInstanceState = Bundle()
        controller.saveInstanceState(savedInstanceState)
        
        // Destroy the activity
        controller.pause()
        controller.stop()
        controller.destroy()

        // Simulate process death / classloader reload by resetting static state
        ModelResolver.AVAILABLE_MODELS.clear()
        ModelResolver.AVAILABLE_MODELS.add(ModelResolver.GEMMA3_CPU)
        ModelResolver.AVAILABLE_MODELS.add(ModelResolver.TINY_GARDEN)

        // 3. Recreate the activity with the saved instance state
        val recreatedController = Robolectric.buildActivity(MainActivity::class.java)
        recreatedController.create(savedInstanceState)
        recreatedController.start()
        recreatedController.restoreInstanceState(savedInstanceState)
        recreatedController.resume()
        recreatedController.visible()
        
        val recreatedActivity = recreatedController.get()
        
        // 4. Verify that selectedModel is restored and not null using reflection
        val selectedModelField = MainActivity::class.java.getDeclaredField("selectedModel")
        selectedModelField.isAccessible = true
        val restoredModel = selectedModelField.get(recreatedActivity) as? ModelConfig
        
        assertNotNull("selectedModel should not be null after recreation", restoredModel)
        assertEquals("my-custom-model", restoredModel?.id)
    }

}
