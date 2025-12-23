package com.guardian.mesh.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream
import java.io.IOException

class RiskModel(private val context: Context) {

    private var tflite: Interpreter? = null

    init {
        initModel()
    }

    private fun initModel() {
        try {
            val model = loadModelFile("risk_model.tflite")
            tflite = Interpreter(model)
            Log.d("RiskModel", "ML Model Loaded Successfully")
        } catch (e: Exception) {
            Log.e("RiskModel", "Error loading ML model (risk_model.tflite). Using heuristic fallback.", e)
        }
    }

    private fun loadModelFile(modelPath: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun predict(features: FloatArray): Float {
        // Step 6: Run Model Internally
        if (tflite != null) {
            try {
                // TFLite expects [batch_size, input_dim] -> [1, 8]
                // And outputs [batch_size, output_dim] -> [1, 1]
                val input = Array(1) { features }
                val output = Array(1) { FloatArray(1) }
                tflite?.run(input, output)
                
                val score = output[0][0]
                Log.d("RiskModel", "TFLite Prediction: $score")
                return score
            } catch (e: Exception) {
                Log.e("RiskModel", "Inference Failed", e)
            }
        }
        
        return heuristicFallback(features)
    }

    private fun heuristicFallback(features: FloatArray): Float {
        // Features Order:
        // 0: screen_time (0.8)
        // 1: loc_delta (0=Home, 1=Away)
        // 2: typing_speed (0.5)
        // 3: typing_variance (0.0=Bot, 1.0=Chaotic)
        // 4: active_hour (0=Normal, 1=Weird)
        // 5: interval (0..1)
        // 6: motion (0=Stable, 1=Shake) -- provided externally in vector
        // 7: bt_peer (1.0=Good)

        Log.d("RiskModel", "Running Heuristic Fallback on Features: ${features.joinToString()}")

        var score = 1.0f // Start with perfect trust

        // Penalty Logic
        if (features[1] > 0.5f) score -= 0.4f // Bad Location -> -40%
        if (features[4] > 0.5f) score -= 0.2f // Weird Hour -> -20%
        if (features[3] > 0.8f) score -= 0.1f // Chaotic Typing -> -10%

        // Rewards
        if (features[7] > 0.8f) score += 0.1f // Trusted Peers nearby -> +10%

        // Clamp
        return score.coerceIn(0.0f, 1.0f)
    }
}
