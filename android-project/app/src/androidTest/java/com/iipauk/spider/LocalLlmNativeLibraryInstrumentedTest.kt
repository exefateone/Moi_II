package com.iipauk.spider

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies CMake/JNI linkage on the current ABI; a real GGUF is supplied separately for inference QA. */
@RunWith(AndroidJUnit4::class)
class LocalLlmNativeLibraryInstrumentedTest {
    @Test
    fun nativeLibraryLoadsAndDoesNotPretendAUserModelIsAlreadyLoaded() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = SpiderAgentRuntime.get(context).localLlmRuntime
        assertTrue("Native llama.cpp library must load from the APK for this ABI", runtime.engineAvailable)
        assertFalse("Model weights are user-supplied and never bundled in the APK", runtime.isModelLoaded)
    }
}
