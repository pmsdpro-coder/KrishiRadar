package com.krishiradar.app.data.model

data class DeviceCapability(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val cpuCores: Int,
    val hasNpu: Boolean,
    val hasGpu: Boolean,
    val gpuVendor: String,
    val androidVersion: Int,
    val abi: List<String>,
    val isArm64: Boolean = abi.contains("arm64-v8a")
) {
    val availableRamGb: Float get() = availableRamBytes / (1024f * 1024f * 1024f)
    val totalRamGb: Float get() = totalRamBytes / (1024f * 1024f * 1024f)
    val freeStorageGb: Float get() = freeStorageBytes / (1024f * 1024f * 1024f)

    val tier: DeviceTier
        get() = when {
            totalRamBytes >= 8L * 1024 * 1024 * 1024 && isArm64 -> DeviceTier.HIGH
            totalRamBytes >= 4L * 1024 * 1024 * 1024 && isArm64 -> DeviceTier.MID
            totalRamBytes >= 2L * 1024 * 1024 * 1024 -> DeviceTier.LOW
            else -> DeviceTier.INSUFFICIENT
        }

    enum class DeviceTier { HIGH, MID, LOW, INSUFFICIENT }
}
