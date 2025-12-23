package com.guardian.mesh.network

import com.guardian.mesh.AppConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object NetworkClient {
    // Use 192.168.0.150 for physical device testing
    // private const val BASE_URL = "http://10.0.2.2:8080" // Emulator
    
    val authService: AuthService by lazy {
        Retrofit.Builder()
            .baseUrl(AppConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthService::class.java)
    }
}
