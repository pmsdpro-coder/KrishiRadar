package com.krishiradar.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.krishiradar.app.ui.screens.diagnosis.DiagnosisScreen
import com.krishiradar.app.ui.screens.modelmanager.DeviceCapabilityScreen
import com.krishiradar.app.ui.screens.modelmanager.ModelManagerScreen

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Diagnosis.route) {
        composable(Screen.Diagnosis.route) {
            DiagnosisScreen(
                onBack = { navController.popBackStack() },
                onNavigateToModelManager = { navController.navigate(Screen.ModelManager.route) }
            )
        }
        composable(Screen.ModelManager.route) {
            ModelManagerScreen(
                onBack = { navController.popBackStack() },
                onViewCapability = { navController.navigate(Screen.DeviceCapability.route) }
            )
        }
        composable(Screen.DeviceCapability.route) {
            DeviceCapabilityScreen(onBack = { navController.popBackStack() })
        }
    }
}
