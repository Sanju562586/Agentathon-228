package com.guardian.mesh

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

class BehavioralEngine(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("BehavioralPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Persistent State
    private var knownLocations: MutableList<LocationData> = loadKnownLocations()
    private var activeHoursStart: Int = prefs.getInt("active_start", 7) // Default 7 AM
    private var activeHoursEnd: Int = prefs.getInt("active_end", 22)   // Default 10 PM
    
    data class LocationData(val lat: Double, val lng: Double, var visitCount: Int) {
        fun toLocation(): Location {
            val l = Location("BehavioralEngine")
            l.latitude = lat
            l.longitude = lng
            return l
        }
    }

    fun learnLocation(location: Location) {
        var found = false
        for (known in knownLocations) {
            val results = FloatArray(1)
            Location.distanceBetween(known.lat, known.lng, location.latitude, location.longitude, results)
            if (results[0] < 100) { // 100m radius
                known.visitCount++
                found = true
                Log.d("BehavioralEngine", "Reinforcing location. Visits: ${known.visitCount}")
                break
            }
        }
        
        if (!found) {
            knownLocations.add(LocationData(location.latitude, location.longitude, 1))
            Log.d("BehavioralEngine", "Learned NEW location: ${location.latitude}, ${location.longitude}")
        }
        
        saveKnownLocations()
        learnTime()
    }

    private fun learnTime() {
        // Simple heuristic: Expand active window if user is active outside it
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (currentHour < activeHoursStart) {
            activeHoursStart = currentHour
            prefs.edit().putInt("active_start", activeHoursStart).apply()
        }
        if (currentHour > activeHoursEnd) {
            activeHoursEnd = currentHour
            prefs.edit().putInt("active_end", activeHoursEnd).apply()
        }
    }

    fun isNormalContext(currentLocation: Location?): Boolean {
        if (currentLocation == null) return false
        
        val isTimeNormal = checkTime()
        val isLocationNormal = checkLocation(currentLocation)
        
        return isTimeNormal && isLocationNormal
    }
    
    fun getTrustSignal(currentLocation: Location?): Float {
        // Returns 0.0 to 1.0 based on context strength
        if (currentLocation == null) return 0.0f
        
        var signal = 0.0f
        if (checkLocation(currentLocation)) signal += 0.6f
        if (checkTime()) signal += 0.4f
        return signal
    }

    private fun checkTime(): Boolean {
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // Allow 1 hour buffer
        return currentHour in (activeHoursStart - 1)..(activeHoursEnd + 1)
    }

    private fun checkLocation(currentLocation: Location): Boolean {
        if (knownLocations.isEmpty()) return true // Trust first use to bootstrap

        for (known in knownLocations) {
            val results = FloatArray(1)
            Location.distanceBetween(known.lat, known.lng, currentLocation.latitude, currentLocation.longitude, results)
            
            // Trusted if < 200m AND visited at least 3 times
            if (results[0] < 200 && known.visitCount >= 3) { 
                return true
            }
        }
        return false
    }

    private fun saveKnownLocations() {
        val json = gson.toJson(knownLocations)
        prefs.edit().putString("known_locations", json).apply()
    }

    // New Signals
    private var lastActiveTime: Long = System.currentTimeMillis()
    private val typingIntervals = mutableListOf<Long>()
    private var screenOnCount: Int = 0
    
    // Config
    private val maxKeystrokeSamples = 50

    fun logActivity() {
        lastActiveTime = System.currentTimeMillis()
    }
    
    fun logKeystroke(timestamp: Long) {
        if (typingIntervals.size > maxKeystrokeSamples) typingIntervals.removeAt(0)
        typingIntervals.add(timestamp)
        logActivity() // Typing is activity
    }
    
    // --- Feature Extraction ---
    
    fun getIdleTimeSeconds(): Long {
        return (System.currentTimeMillis() - lastActiveTime) / 1000
    }
    
    fun getTypingVariance(): Float {
        if (typingIntervals.size < 5) return 0.0f
        
        // Calculate intervals between keypresses
        val deltas = mutableListOf<Long>()
        for (i in 0 until typingIntervals.size - 1) {
            deltas.add(typingIntervals[i+1] - typingIntervals[i])
        }
        
        val mean = deltas.average()
        var varianceSum = 0.0
        for (d in deltas) {
            varianceSum += Math.pow(d - mean, 2.0)
        }
        
        // Normalize: A chaotic typist (high variance) might be suspicious if normally low
        // or a bot (0 variance). Returns simplified variance score 0..1
        val variance = (varianceSum / deltas.size).toFloat()
        return (variance / 10000f).coerceIn(0.0f, 1.0f) 
    }
    
    fun getScreenTimeScore(): Float {
        // Mock: Ratio of how much "screen on" time vs idle.
        // In real impl, use UsageStats. Here we assume constant foreground use implies 1.0
        return 0.8f // Placeholder
    }
    
    fun getBluetoothPeerScore(): Float {
        // Placeholder: Ratio of known devices nearby
        return 1.0f 
    }
    
    fun toFeatureVector(currentLocation: Location?): FloatArray {
        // [screen_time, location_delta, typing_speed, typing_var, active_hour, interval_gap, motion, bt_score]
        
        val locDelta = if (checkLocation(currentLocation ?: Location(""))) 0.0f else 1.0f // 0 = Home(Safe), 1 = Away
        val interval = (getIdleTimeSeconds() / 3600f).coerceIn(0.0f, 1.0f) // Normalized hours
        
        val activeHourScore = if (checkTime()) 0.0f else 1.0f // 0 = Normal time, 1 = Weird time
        
        return floatArrayOf(
            getScreenTimeScore(),       // 1. Screen Time Ratio
            locDelta,                   // 2. Location Deviation
            0.5f,                       // 3. Typing Speed (Mock)
            getTypingVariance(),        // 4. Typing Variance
            activeHourScore,            // 5. Active Hour Score
            interval,                   // 6. Interval Gap
            0.0f,                       // 7. Motion Stability (handled by MotionMonitor)
            getBluetoothPeerScore()     // 8. BT Peer Score
        )
    }

    private fun loadKnownLocations(): MutableList<LocationData> {
        val json = prefs.getString("known_locations", null)
        return if (json != null) {
            val type = object : TypeToken<MutableList<LocationData>>() {}.type
            gson.fromJson(json, type)
        } else {
            mutableListOf()
        }
    }
}
