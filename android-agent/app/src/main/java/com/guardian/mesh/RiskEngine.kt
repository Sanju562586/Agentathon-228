package com.guardian.mesh

import android.util.Log

import android.content.Context

class RiskEngine(
    private val context: Context, // Added Context for Model
    private val motionMonitor: MotionMonitor,
    private val locationMonitor: LocationMonitor,
    private val meshMonitor: MeshMonitor,
    private val behavioralEngine: BehavioralEngine
) {

    private val riskModel = com.guardian.mesh.ml.RiskModel(context)

    data class TrustScore(
        val totalScore: Float, // 0.0 to 1.0 (1.0 = High Trust)
        val details: String    // Debug string for UI/Logs
    )

    fun calculateTrustScore(faceConfidence: Float): TrustScore {
        // Step 1 & 2: Collect Signals & Convert to Features
        val location = locationMonitor.getLastLocation()
        if (location != null) behavioralEngine.learnLocation(location)

        // Base Vector from Behavioral Engine [screen, loc, type_spd, type_var, active, interval, MOTION, bt]
        // Motion is index 6. BT is index 7.
        val features = behavioralEngine.toFeatureVector(location)
        
        // Enrich with Motion (Index 6)
        val motionState = motionMonitor.currentMotionState
        features[6] = when (motionState) {
            MotionMonitor.MotionState.STATIONARY -> 0.0f // Stable
            MotionMonitor.MotionState.MOVING -> 0.5f     // Walking
            MotionMonitor.MotionState.HIGH_VELOCITY -> 1.0f // Driving (Risky)
        }

        // Enrich with Mesh/BT (Index 7) - BehavioralEngine had a placeholder
        val neighbors = meshMonitor.getNeighborCount()
        features[7] = if (neighbors > 0) 1.0f else 0.0f // 1.0 = Trusted Peers Present

        // Step 6: Run ML Model
        val trustProbability = riskModel.predict(features) // 0.0 to 1.0
        val riskScore = (1.0f - trustProbability) * 100 // 0 to 100

        val sb = StringBuilder()
        sb.append("ML Risk: ${riskScore.toInt()}% (Trust: $trustProbability) ")
        sb.append("| Feats: ${features.joinToString(", ", transform = { "%.1f".format(it) })} ")

        // Step 7: Agent Decision Policy
        // Risk < 30 -> Silent Auth (Trust 1.0)
        // Risk 30-60 -> Verify (Biometric Check)
        // Risk > 60 -> Block
        
        var finalTrust = 0.0f
        
        if (riskScore < 30) {
            finalTrust = 1.0f
            sb.append("| Decision: SILENT ALLOW")
        } else if (riskScore < 60) {
            // "Ask biometric"
            // If we have high face confidence provided by the caller, we upgrade to Allow
            if (faceConfidence > 0.8f) {
                finalTrust = 1.0f
                sb.append("| Decision: BIO VERIFIED (Face: $faceConfidence)")
            } else {
                finalTrust = 0.5f // Medium trust, will trigger Manual Approval/Face in UI
                sb.append("| Decision: REQUEST BIO")
            }
        } else {
            finalTrust = 0.0f
            sb.append("| Decision: BLOCK")
        }

        // DEBUG OVERRIDE for Dev until we have real data
        val debugBoost = 0.0f // Set to 0.0 now that we have a 'real' model logic
        val boosted = (finalTrust + debugBoost).coerceIn(0.0f, 1.0f)
        
        Log.d("RiskEngine", sb.toString())
        return TrustScore(boosted, sb.toString())
    }
}
