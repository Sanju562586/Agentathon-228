package com.guardian.mesh

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.guardian.mesh.crypto.IdentityManager
import com.guardian.mesh.ui.LoginActivity
import com.guardian.mesh.ui.RegistrationActivity

class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // DEBUG: Verify State
        val prefs = getSharedPreferences("user_session", android.content.Context.MODE_PRIVATE)
        val loggedInEmail = prefs.getString("logged_in_email", null)
        val identityManager = IdentityManager()
        val hasIdentity = identityManager.hasIdentity()
        
        android.widget.Toast.makeText(this, "Launcher: ID=$hasIdentity, Email=$loggedInEmail", android.widget.Toast.LENGTH_LONG).show()

        if (loggedInEmail != null) {
            // Already Logged In -> Go to Dashboard
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        } else if (hasIdentity) {
            // Identity exists but session expired/cleared -> Go to Login
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        } else {
            // New User -> Go to Registration
            val intent = Intent(this, RegistrationActivity::class.java)
            startActivity(intent)
        }
        
        finish()
    }
}
