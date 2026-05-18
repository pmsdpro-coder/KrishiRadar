package com.krishiradar.app.ui.navigation

sealed class Screen(val route: String) {
    data object Diagnosis : Screen("diagnosis")
    data object ModelManager : Screen("model_manager")
    data object DeviceCapability : Screen("device_capability")
}
