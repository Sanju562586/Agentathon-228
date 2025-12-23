package com.guardian.mesh.autofill

data class Credential(
    val id: String,
    val packageName: String,
    val username: String,
    val password: String,
    val appName: String
)

object CredentialVault {
    private val credentials = mutableListOf<Credential>()
    
    // Default Identity
    var defaultEmail: String? = "ramcharanpolabathina@gmail.com" // Updated from User Logs
    var defaultPhone: String? = "+15550001234" // Default Phone
    
    init {
        credentials.addAll(listOf(
            Credential("1", "com.instagram.android", "insta_fan_2024", "social_secure_77", "Instagram"),
            Credential("2", "com.facebook.katana", "fb_user_prime", "meta_verse_99", "Facebook"),
            Credential("3", "netflix.com", "chitturirohith333@gmail.com", "Rohith*333", "Netflix"),
            // Explicit generic Netflix for web testing - Second Account to trigger Popup
            Credential("3_web", "netflix.com", "ramcharanpolabathina@gmail.com", "7386468690RAM@", "Netflix"),
            Credential("4", "com.android.chrome", "chrome_surfer", "web_safe_pass", "Chrome"),
            Credential("5", "org.mozilla.firefox", "firefox_user", "browser_pass_789", "Firefox"),
            Credential("8", "www.roblox.com", "gamer_pro_99", "roblox_pass_123", "Roblox"),
            Credential("9", "com.google.android.gm", "ramcharanpolabathina@gmail.com", "7386468690RAM@", "Gmail"),
            Credential("10", "www.amazon.com", "shopper_pro@amazon.com", "prime_time_55", "Amazon"),
            Credential("11", "www.linkedin.com", "career_climber@linked.in", "resumepassword", "LinkedIn"),
            Credential("12", "leetcode.com", "coder_master_99", "leetcode_pass_123", "LeetCode"),
            Credential("13", "github.com", "git_commit_push", "hub_password_secure", "GitHub")
        ))
    }

    fun getCredentials(packageName: String): List<Credential> {
        return credentials.filter { it.packageName == packageName }
    }
    
    fun getAllCredentials(): List<Credential> {
        return credentials.toList()
    }

    fun addCredential(credential: Credential) {
        credentials.add(credential)
    }
    
    fun removeCredential(id: String) {
        credentials.removeAll { it.id == id }
    }
    
    fun getAllPackages(): Set<String> {
        return credentials.map { it.packageName }.toSet()
    }
    
    // Legacy support helper (returns first match)
    fun getLegacyCredentials(packageName: String): Pair<String, String>? {
        val match = credentials.firstOrNull { it.packageName == packageName }
        return match?.let { Pair(it.username, it.password) }
    }
}
