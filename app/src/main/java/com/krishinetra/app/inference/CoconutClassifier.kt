package com.krishiradar.app.inference

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ClassificationResult(
    val diseaseLabel: String,
    val displayLabel: String,
    val confidence: Float,
    val tier: ConfidenceTier,
    val alternatives: List<Pair<String, Float>>
)

enum class ConfidenceTier { HIGH, MEDIUM, LOW }

private fun String.toDisplayLabel(): String =
    replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")

class CoconutClassifier(context: Context) {

    private val interpreter: Interpreter
    private val classNames: List<String>
    private val imageProcessor: ImageProcessor

    init {
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(loadModelFile(context, "coconut_classifier.tflite"), options)

        val json = context.assets.open("class_names.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        classNames = (0 until array.length()).map { array.getString(it) }

        // FP32 model — resize to 224×224 via bilinear; no normalization (EfficientNet handles it)
        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
            .build()
    }

    fun classify(bitmap: Bitmap): ClassificationResult {
        if (bitmap.width < 100 || bitmap.height < 100) {
            return ClassificationResult(
                diseaseLabel = "ImageTooSmall",
                displayLabel = "Image Too Small",
                confidence = 0f,
                tier = ConfidenceTier.LOW,
                alternatives = emptyList()
            )
        }

        // Ensure ARGB_8888 (strips alpha correctly when drawing to new bitmap)
        val rgbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        // FP32 input: pixel values 0–255 as floats (EfficientNet normalizes internally)
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(rgbBitmap)
        val processed = imageProcessor.process(tensorImage)

        val output = Array(1) { FloatArray(classNames.size) }
        interpreter.run(processed.buffer, output)

        val probs = output[0]
        val total = probs.sum().coerceAtLeast(1e-6f)
        val normalized = FloatArray(probs.size) { probs[it] / total }

        val sorted = normalized.mapIndexed { idx, conf -> idx to conf }
            .sortedByDescending { it.second }
            .take(3)

        val (topIdx, topConf) = sorted[0]
        val label = classNames[topIdx]

        return ClassificationResult(
            diseaseLabel = label,
            displayLabel = label.toDisplayLabel(),
            confidence = topConf,
            tier = when {
                topConf >= 0.85f -> ConfidenceTier.HIGH
                topConf >= 0.60f -> ConfidenceTier.MEDIUM
                else -> ConfidenceTier.LOW
            },
            alternatives = sorted.drop(1).map { (idx, conf) ->
                classNames[idx].toDisplayLabel() to conf
            }
        )
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val fd = context.assets.openFd(filename)
        return FileInputStream(fd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
        )
    }

    fun close() = interpreter.close()
}
