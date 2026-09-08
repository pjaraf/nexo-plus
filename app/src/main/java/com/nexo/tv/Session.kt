package com.nexo.tv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Session {
    /** Servidor único y oficial de la app */
    const val SERVER = "https://nexo.fusionx.cl"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        val ctx = context.applicationContext
        prefs = try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "nexo_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            Log.e("Session", "encrypted prefs failed, using plain", e)
            ctx.getSharedPreferences("nexo_session_plain", Context.MODE_PRIVATE)
        }
        // Eliminar cualquier URL previa guardada y asegurar el servidor oficial
        val current = prefs.getString("server", null)
        if (current != null && current != SERVER) {
            prefs.edit().putString("server", SERVER).apply()
        }
    }

    var username: String
        get() = prefs.getString("user", "") ?: ""
        set(value) { prefs.edit().putString("user", value).apply() }

    var password: String
        get() = prefs.getString("pass", "") ?: ""
        set(value) { prefs.edit().putString("pass", value).apply() }

    var server: String
        get() {
            val stored = prefs.getString("server", SERVER) ?: SERVER
            if (stored.isBlank() || stored != SERVER) {
                prefs.edit().putString("server", SERVER).apply()
                return SERVER
            }
            return stored
        }
        set(value) {
            val clean = if (value.isBlank() || value.contains("elite") || value.contains("10.250.") || value.contains("192.168.")) {
                SERVER
            } else {
                value.trimEnd('/')
            }
            prefs.edit().putString("server", clean).apply()
        }

    val isLoggedIn: Boolean get() = username.isNotBlank() && password.isNotBlank()

    fun login(user: String, pass: String) {
        username = user
        password = pass
    }

    fun logout() {
        prefs.edit().clear().apply()
    }
}
