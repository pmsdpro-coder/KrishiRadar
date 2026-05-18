package com.krishiradar.app

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.krishiradar.app.inference.InferenceManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class KrishiRadarApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var inferenceManager: InferenceManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Register for system memory callbacks so the model can be released under pressure
        registerComponentCallbacks(inferenceManager)
    }

    override fun onTerminate() {
        unregisterComponentCallbacks(inferenceManager)
        super.onTerminate()
    }

    // onTrimMemory is handled by registerComponentCallbacks(inferenceManager) above.
    // No manual forwarding needed — calling it here would double-fire the callback.
}
