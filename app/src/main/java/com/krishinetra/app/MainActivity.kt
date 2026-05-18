package com.krishiradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.krishiradar.app.inference.InferenceManager
import com.krishiradar.app.ui.navigation.NavGraph
import com.krishiradar.app.ui.theme.KrishiRadarTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var inferenceManager: InferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pre-warm model if user has one set up (non-blocking — runs on IO thread)
        inferenceManager.preWarmIfAppropriate()

        setContent {
            KrishiRadarTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }

    // onTrimMemory forwarding removed — InferenceManager is registered via
    // Application.registerComponentCallbacks, which already covers all memory events.
}
