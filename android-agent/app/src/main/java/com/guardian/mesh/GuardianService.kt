package com.guardian.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.guardian.mesh.crypto.KeyManager
import com.guardian.mesh.network.NetworkClient
import com.guardian.mesh.network.VerifyRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class GuardianService : LifecycleService() {

    private lateinit var motionMonitor: MotionMonitor
    private lateinit var locationMonitor: LocationMonitor
    private lateinit var meshMonitor: MeshMonitor
    private lateinit var behavioralEngine: BehavioralEngine
    private lateinit var riskEngine: RiskEngine
    private lateinit var keyManager: KeyManager
    private lateinit var deviceId: String
    // Vision Enforcer (Passive Eyes)
    private lateinit var visionEnforcer: VisionEnforcer
    // Sentry (Brain) - Initialized in loop currently, could be property
    
    // Volatile Trust Metrics
    private var currentFaceConfidence: Float = 0.0f

    companion object {
        var activeRiskEngine: RiskEngine? = null
        var activeMeshMonitor: MeshMonitor? = null
        
        @Volatile
        private var instance: GuardianService? = null

        fun pauseCamera() {
            try {
                instance?.visionEnforcer?.stopScanning()
                Log.d("GuardianService", "⏸️ Service Camera Paused for App Activity")
            } catch (e: Exception) {
                Log.e("GuardianService", "Failed to pause camera: ${e.message}")
            }
        }

        fun resumeCamera() {
             try {
                instance?.visionEnforcer?.startScanning()
                 Log.d("GuardianService", "▶️ Service Camera Resumed")
            } catch (e: Exception) {
                Log.e("GuardianService", "Failed to resume camera: ${e.message}")
            }
        }
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("GuardianService", "Service Created")
        startForegroundService()
        
        motionMonitor = MotionMonitor(this) {
            Log.e("GuardianService", "SHAKE DETECTED! DURESS!")
        }
        // Battery Optimization: Wake Eyes only on Motion
        motionMonitor.onMotionStateChange = { state ->
            if (state == MotionMonitor.MotionState.STATIONARY) {
                // Delay stop slightly to capture "setting down" face? 
                // For now, immediate stop to be battery aggressive as requested.
                // visionEnforcer.stopScanning() // FIXED: User reported issue holding phone still. Keeping scan active.
            } else {
                visionEnforcer.startScanning()
            }
        }

        locationMonitor = LocationMonitor(this)
        meshMonitor = MeshMonitor(this)
        behavioralEngine = BehavioralEngine(this)
        
        // Passive Vision Initialization
        visionEnforcer = VisionEnforcer(this, this)
        visionEnforcer.onFaceDetected = { count, isAlive ->
            // Update the volatile trust metric based on vision
            currentFaceConfidence = if (isAlive) 1.0f else if (count > 0) 0.5f else 0.0f
        }
        
        riskEngine = RiskEngine(this, motionMonitor, locationMonitor, meshMonitor, behavioralEngine)
        activeRiskEngine = riskEngine
        
        keyManager = KeyManager()
        deviceId = retrieveDeviceId() 

        motionMonitor.start()
        locationMonitor.start()
        meshMonitor.start()
        // visionEnforcer.startScanning() // Removed: Event-driven now
        
        startAuthLoop()
        startAgentLoop()
    }

    private fun startAgentLoop() {
        serviceScope.launch {
            // Sentry AI Initialization
            val sentryAI = SentryAI(riskEngine)
            
            while (isActive) {
                try {
                    val response = NetworkClient.authService.getPendingRequests().execute()
                    if (response.isSuccessful && response.body() != null) {
                        val requests = response.body()!!
                        for (req in requests) {
                            Log.d("GuardianService", "CLOUD AGENT: Request from ${req.source} for ${req.service}")
                            
                            // Wake Vision
                            visionEnforcer.startScanning()
                            delay(500)
                            
                            // DEBUG OVERRIDE: Force High Confidence for Testing
                            currentFaceConfidence = 1.0f 
                            Log.w("GuardianService", "⚠️ DEBUG: Forcing Face Confidence to 1.0f for testing!") 
                            
                            // 1. Sentry Evaluation
                            val sentryState = sentryAI.evaluate(currentFaceConfidence)
                            
                            // 2. Global Trust Check (Used for Sentry decisions)
                            val allTrustedDevices = com.guardian.mesh.autofill.TrustedDeviceStore.getDevices(this@GuardianService)
                            val isTrustedDevice = allTrustedDevices.any { 
                                fun cleanKey(k: String?): String {
                                    if (k == null) return ""
                                    return k.replace("-----BEGIN PUBLIC KEY-----", "")
                                            .replace("-----END PUBLIC KEY-----", "")
                                            .replace("\\s".toRegex(), "")
                                }
                                val stored = cleanKey(it.publicKey)
                                val incoming = cleanKey(req.publicKey)
                                stored.length >= 20 && incoming.length >= 20 && stored == incoming 
                            }
                            
                            Log.d("GuardianService", "Sentry State: $sentryState | Device Trusted: $isTrustedDevice")

                            // --- SPECIAL HANDLING: PAIRING CHECK ---
                            if (req.service == "pairing_check") {
                                Log.d("GuardianService", "🔍 Processing Pairing Check...")
                                Log.d("GuardianService", "🔍 Trust Store: Found ${allTrustedDevices.size} devices.")
                                
                                val incomingKey = req.publicKey
                                var isPairingTrusted = false // Local var for clarity
                                
                                if (incomingKey != null) {
                                    val cleanIncoming = incomingKey
                                        .replace("-----BEGIN PUBLIC KEY-----", "")
                                        .replace("-----END PUBLIC KEY-----", "")
                                        .replace("\\s".toRegex(), "")

                                    Log.d("GuardianService", "📥 Incoming Key: ${cleanIncoming.take(20)}...")

                                    for (device in allTrustedDevices) {
                                        val cleanStored = device.publicKey
                                            .replace("-----BEGIN PUBLIC KEY-----", "")
                                            .replace("-----END PUBLIC KEY-----", "")
                                            .replace("\\s".toRegex(), "")

                                        Log.d("GuardianService", "💾 Stored Key: ${cleanStored.take(20)}...")
                                        
                                        if (cleanIncoming == cleanStored) {
                                            isPairingTrusted = true
                                            Log.d("GuardianService", "✅ MATCH FOUND! Device: ${device.name}")
                                            break
                                        } else {
                                            Log.d("GuardianService", "❌ Mismatch: ${device.name}")
                                        }
                                    }
                                } else {
                                     Log.e("GuardianService", "⛔ No Public Key in Request")
                                }

                                val status = if (isPairingTrusted) "PAIRED" else "UNPAIRED"
                                val rawPayload = "{\"status\": \"$status\"}"
                                Log.d("GuardianService", "📦 Payload: $rawPayload")
                                
                                // ENCRYPT
                                val encryptedPayload = if (incomingKey != null) {
                                    try {
                                        val enc = encryptPayload(rawPayload, incomingKey)
                                        Log.d("GuardianService", "🔒 Encrypted (${enc.length} chars)")
                                        enc
                                    } catch (e: Exception) {
                                        Log.e("GuardianService", "🔥 Encryption Failed: ${e.message}")
                                        rawPayload
                                    }
                                } else {
                                    rawPayload 
                                }
                                
                                val agentResp = com.guardian.mesh.network.AgentResponse(req.requestId, encryptedPayload)
                                
                                NetworkClient.authService.respondToRequest(agentResp).enqueue(object : retrofit2.Callback<Void> {
                                    override fun onResponse(call: retrofit2.Call<Void>, response: retrofit2.Response<Void>) {}
                                    override fun onFailure(call: retrofit2.Call<Void>, t: Throwable) { Log.e("GuardianService", "Net Fail: ${t.message}") }
                                })
                                
                                if (isPairingTrusted) {
                                    val notification = NotificationCompat.Builder(this@GuardianService, "GuardianServiceChannel")
                                        .setContentTitle("Guardian Link Active")
                                        .setContentText("Connected to Chrome Extension")
                                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                                        .setPriority(NotificationCompat.PRIORITY_MAX)
                                        .setDefaults(Notification.DEFAULT_ALL)
                                        .setAutoCancel(true)
                                        .build()
                                    getSystemService(NotificationManager::class.java).notify(req.requestId.hashCode(), notification)
                                }
                                continue // Skip Sentry logic for pairing checks
                            }

                            // --- SPECIAL HANDLING: OTP REQUEST ---
                            if (req.service == "otp_request") {
                                Log.d("GuardianService", "🔢 Processing OTP Request...")
                                val latestOtp = com.guardian.mesh.otp.OtpRepository.getLatestOtp()
                                val otpPayload = if (latestOtp != null) {
                                    "{\"otp\": \"${latestOtp.code}\", \"source\": \"${latestOtp.sourcePackage}\"}"
                                } else {
                                    // Simulation Failover: If no real OTP, return nothing or error
                                    Log.w("GuardianService", "⚠️ No OTP found.")
                                    "{\"error\": \"No recent OTP found\"}"
                                }

                                encryptAndSend(req.requestId, otpPayload, req.publicKey)
                                continue
                            }
                            var matches = emptyList<Map<String, String>>()
                            var isDecoy = false

                            when (sentryState) {
                                SentryState.SAFE -> {
                                    Log.d("GuardianService", "SENTRY: SAFE. Configuring Real Credentials.")
                                    val creds = com.guardian.mesh.autofill.CredentialVault.getAllCredentials()
                                    matches = findMatches(creds, req.service)
                                    
                                    if (!isTrustedDevice) {
                                        Log.w("GuardianService", "SENTRY: SAFE but Device Unknown. Matches found: ${matches.size}")
                                    }
                                }
                                SentryState.DANGER -> {
                                    Log.e("GuardianService", "SENTRY: DANGER! HONEYPOT.")
                                    val decoys = com.guardian.mesh.autofill.HoneypotVault.getDecoy(req.service)
                                    matches = decoys.map { 
                                        mapOf("username" to it.username, "password" to it.password, "appName" to it.appName) 
                                    }
                                    isDecoy = true
                                }
                                SentryState.SUSPICIOUS -> {
                                    Log.w("GuardianService", "SENTRY: SUSPICIOUS.")
                                }
                            }
                            
                            // Execute Response
                            if (matches.isNotEmpty() && (sentryState == SentryState.SAFE || sentryState == SentryState.DANGER)) {
                                if (sentryState == SentryState.SAFE && !isTrustedDevice) {
                                    requestManualApproval(req, "New Device detected. Trust Score High but Verification Needed.")
                                } else {
                                    sendCredentials(req, matches)
                                    if (isDecoy) Log.d("GuardianService", "SENTRY: Decoy sent.")
                                }
                            } else {
                                if (sentryState != SentryState.DANGER) {
                                    requestManualApproval(req, "Sentry Check: ${sentryState.name}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GuardianService", "Agent Loop Error: ${e.message}")
                }
                delay(3000) 
            }
        }
    }
    
    private fun sendCredentials(req: com.guardian.mesh.network.AgentRequest, matches: List<Map<String, String>>) {
        Log.d("GuardianService", "✅ ACTION: Auto-Filling Credentials (SILENT ALLOW) - ${matches.size} candidate(s) sent")
        val jsonArray = org.json.JSONArray()
        for (m in matches) {
            val obj = org.json.JSONObject()
            m.forEach { (k, v) -> obj.put(k, v) }
            jsonArray.put(obj)
        }
        val rawPayload = jsonArray.toString()
        encryptAndSend(req.requestId, rawPayload, req.publicKey)
    }

    private fun encryptAndSend(requestId: String, rawPayload: String, publicKey: String?) {
        Log.d("GuardianService", "📦 Payload: $rawPayload")
        
        val encryptedPayload = if (publicKey != null) {
            try {
                val enc = encryptPayload(rawPayload, publicKey)
                Log.d("GuardianService", "🔒 Encrypted (${enc.length} chars)")
                enc
            } catch (e: Exception) {
                Log.e("GuardianService", "🔥 Encryption Failed: ${e.message}")
                rawPayload
            }
        } else {
            rawPayload 
        }
        
        val agentResp = com.guardian.mesh.network.AgentResponse(requestId, encryptedPayload)
        
        NetworkClient.authService.respondToRequest(agentResp).enqueue(object : retrofit2.Callback<Void> {
            override fun onResponse(call: retrofit2.Call<Void>, response: retrofit2.Response<Void>) {}
            override fun onFailure(call: retrofit2.Call<Void>, t: Throwable) { Log.e("GuardianService", "Net Fail: ${t.message}") }
        })
    }
    
    // Helper methods for cleanliness
    private fun findMatches(creds: List<com.guardian.mesh.autofill.Credential>, service: String): List<Map<String, String>> {
        Log.d("GuardianService", "🔍 MATCHING: Finding credentials for service: '$service'")
        val matches = mutableListOf<Map<String, String>>()
        for (c in creds) {
            // Bidirectional check for domain robustness
            // 1. App Name matches Service (e.g. "Netflix" inside "netflix.com" or "netflix" inside "Netflix")
            val appMatch = c.appName.contains(service, ignoreCase = true) || service.contains(c.appName, ignoreCase = true)
            
            // 2. Package matches Service (e.g. "com.netflix" inside "netflix" or vice versa)
            val pkgMatch = c.packageName.contains(service, ignoreCase = true) || service.contains(c.packageName, ignoreCase = true)
            
            if (appMatch || pkgMatch) {
                Log.d("GuardianService", "   ✅ Match found: ${c.appName} (${c.packageName})")
                matches.add(mapOf(
                    "username" to c.username,
                    "password" to c.password,
                    "appName" to c.appName,
                    "email" to c.username, // Implicit mapping for Identity requests
                    "phone" to c.username  // Implicit mapping
                ))
            }
        }
        if (matches.isEmpty()) Log.d("GuardianService", "   ⚠️ No matches found for '$service'")
        return matches
    }
    

    
    private fun requestManualApproval(req: com.guardian.mesh.network.AgentRequest, reason: String) {
        Log.w("GuardianService", "⚠️ ACTION: Triggering Manual Approval (INTERVENTION REQUIRED). Reason: $reason")
        
        val intent = Intent(this@GuardianService, com.guardian.mesh.ui.AuthRequestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("requestId", req.requestId)
            putExtra("service", req.service)
            putExtra("source", req.source)
            putExtra("publicKey", req.publicKey)
            putExtra("trustDetails", reason)
        }
        
        val pendingIntent = android.app.PendingIntent.getActivity(
            this@GuardianService, 
            req.requestId.hashCode(), 
            intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(this@GuardianService, "GuardianServiceChannel")
            .setContentTitle("Login Request: ${req.service}")
            .setContentText(reason)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
            
        getSystemService(NotificationManager::class.java).notify(req.requestId.hashCode(), notification)
    }

    // Helper for E2EE (Unchanged)
    private fun encryptPayload(data: String, publicKeyPem: String): String {
        try {
            val pemContent = publicKeyPem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "") 

            val keyBytes = android.util.Base64.decode(pemContent, android.util.Base64.DEFAULT)
            val spec = java.security.spec.X509EncodedKeySpec(keyBytes)
            val keyFactory = java.security.KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(spec)

            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, publicKey)
            
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            return android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("GuardianService", "Crypto Error: ${e.message}")
            throw e
        }
    }
    
    private fun startAuthLoop() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val response = NetworkClient.authService.getChallenge().execute()
                    if (response.isSuccessful && response.body() != null) {
                        val challengeResponse = response.body()!!

                        val signature = keyManager.signData(challengeResponse.challenge)
                        val trustMetric = riskEngine.calculateTrustScore(currentFaceConfidence) // Background check using LIVE confidence
                        val publicKey = keyManager.getPublicKeyPEM()

                        if (signature != null) {
                            val verifyCall = NetworkClient.authService.verify(
                                VerifyRequest(
                                    deviceId = deviceId,
                                    publicKey = publicKey,
                                    challenge = challengeResponse.challenge,
                                    signature = signature,
                                    riskScore = trustMetric.totalScore // Using "riskScore" field to transport Trust Score for now
                                )
                            )
                            val verifyResponse = verifyCall.execute()
                            
                            if (verifyResponse.isSuccessful) {
                                Log.d("GuardianService", "✅ ACTION: Background Verify PASS (Cloud Acknowledged). Score: ${trustMetric.totalScore}")
                            } else {
                                Log.e("GuardianService", "❌ ACTION: Fail. Cloud Rejected. Code: ${verifyResponse.code()}")
                            }
                        }
                    }
            } catch (e: Exception) {
                    Log.e("GuardianService", "Auth Loop Error: ${e.message}")
                }
                delay(10000) 
            }
        }
    }

    private fun retrieveDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        motionMonitor.stop()
        locationMonitor.stop()
        meshMonitor.stop()
        activeRiskEngine = null
        activeMeshMonitor = null
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    private fun startForegroundService() {
        val channelId = "GuardianServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Guardian Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Guardian Mesh Active")
            .setContentText("Protecting your identity in the background")
            .setSmallIcon(android.R.drawable.ic_secure)
            .build()

        startForeground(1, notification)
    }
}
