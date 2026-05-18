package com.krishiradar.app.data.model

enum class ModelVariant(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeBytes: Long,
    val displaySize: String,
    val minRamBytes: Long,
    val recommendedRamBytes: Long,
    val minStorageBytes: Long,
    val parameterCount: String,
    val huggingFaceRepo: String,
    val filename: String,
    val expectedChecksum: String,
    val supportsVision: Boolean = true
) {
    GEMMA4_E2B(
        id = "gemma4-e2b-it",
        displayName = "Gemma 4 E2B",
        description = "Efficient 2B model for most devices. Supports vision and text.",
        sizeBytes = 2_580_000_000L,
        displaySize = "2.6 GB",
        minRamBytes = 3L * 1024 * 1024 * 1024,
        recommendedRamBytes = 4L * 1024 * 1024 * 1024,
        minStorageBytes = 3L * 1024 * 1024 * 1024,
        parameterCount = "2B",
        huggingFaceRepo = "litert-community/gemma-4-E2B-it-litert-lm",
        filename = "gemma-4-E2B-it.litertlm",
        expectedChecksum = "",
        supportsVision = true
    ),

    GEMMA4_E4B(
        id = "gemma4-e4b-it",
        displayName = "Gemma 4 E4B",
        description = "Higher quality 4B model. Requires a capable device with ≥4 GB RAM.",
        sizeBytes = 3_650_000_000L,
        displaySize = "3.7 GB",
        minRamBytes = 4L * 1024 * 1024 * 1024,
        recommendedRamBytes = 6L * 1024 * 1024 * 1024,
        minStorageBytes = 4L * 1024 * 1024 * 1024,
        parameterCount = "4B",
        huggingFaceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
        filename = "gemma-4-E4B-it.litertlm",
        expectedChecksum = "",
        supportsVision = true
    );

    val downloadUrl: String
        get() = "https://huggingface.co/$huggingFaceRepo/resolve/main/$filename"
}

data class ModelRecommendation(
    val variant: ModelVariant,
    val reason: String,
    val adequateHardware: Boolean,
    val warningMessage: String? = null
)
