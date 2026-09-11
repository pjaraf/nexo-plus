package com.nexo.tv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nexo.tv.data.UserInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Session {
    /** Servidor unico de la app */
    const val SERVER = "http://eliteplusec.com:8080"

    /** Hosts oficiales que la app puede usar */
    val HOSTS: List<String> = listOf(SERVER)

    private lateinit var prefs: SharedPreferences

    private fun normalize(url: String): String = url.trim().trimEnd('/')

    private fun isOfficialHost(url: String): Boolean {
        val n = normalize(url)
        return HOSTS.any { normalize(it).equals(n, ignoreCase = true) }
    }

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
        // Forzar el servidor unico (migrar fusionx u otros)
        val current = prefs.getString("server", null)
        if (current == null || !isOfficialHost(current)) {
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
            val n = normalize(stored)
            if (!isOfficialHost(n)) {
                prefs.edit().putString("server", SERVER).apply()
                return SERVER
            }
            return n
        }
        set(value) {
            val clean = normalize(value)
            val final = if (isOfficialHost(clean)) clean else SERVER
            prefs.edit().putString("server", final).apply()
        }

    val isLoggedIn: Boolean get() = username.isNotBlank() && password.isNotBlank()

    /** Datos de cuenta del cliente (user_info Xtream), sin servidor. */
    var accountStatus: String
        get() = prefs.getString("acc_status", "") ?: ""
        private set(value) { prefs.edit().putString("acc_status", value).apply() }

    var accountExpDate: String
        get() = prefs.getString("acc_exp", "") ?: ""
        private set(value) { prefs.edit().putString("acc_exp", value).apply() }

    var accountCreatedAt: String
        get() = prefs.getString("acc_created", "") ?: ""
        private set(value) { prefs.edit().putString("acc_created", value).apply() }

    var accountMaxConnections: String
        get() = prefs.getString("acc_max_cons", "") ?: ""
        private set(value) { prefs.edit().putString("acc_max_cons", value).apply() }

    var accountActiveConnections: String
        get() = prefs.getString("acc_active_cons", "") ?: ""
        private set(value) { prefs.edit().putString("acc_active_cons", value).apply() }

    var accountIsTrial: String
        get() = prefs.getString("acc_trial", "") ?: ""
        private set(value) { prefs.edit().putString("acc_trial", value).apply() }

    var accountMessage: String
        get() = prefs.getString("acc_message", "") ?: ""
        private set(value) { prefs.edit().putString("acc_message", value).apply() }

    fun login(user: String, pass: String) {
        username = user
        password = pass
    }

    fun saveAccountInfo(info: UserInfo?) {
        if (info == null) return
        info.username?.trim()?.takeIf { it.isNotBlank() }?.let { username = it }
        accountStatus = info.status?.trim().orEmpty()
        accountExpDate = formatXtreamDate(info.expDate)
        accountCreatedAt = formatXtreamDate(info.createdAt)
        accountMaxConnections = cleanNum(info.maxConnections)
        accountActiveConnections = cleanNum(info.activeCons)
        accountIsTrial = when {
            isTruthy(info.isTrial) -> "Si"
            info.isTrial == null -> ""
            else -> "No"
        }
        accountMessage = info.message?.trim().orEmpty()
    }

    fun clearAccountInfo() {
        prefs.edit()
            .remove("acc_status")
            .remove("acc_exp")
            .remove("acc_created")
            .remove("acc_max_cons")
            .remove("acc_active_cons")
            .remove("acc_trial")
            .remove("acc_message")
            .apply()
    }

    fun logout() {
        prefs.edit().clear().apply()
        // Tras clear, restaurar host oficial para el proximo login.
        prefs.edit().putString("server", SERVER).apply()
    }

    private fun cleanNum(raw: Any?): String {
        val s = raw?.toString()?.trim().orEmpty()
        if (s.isEmpty() || s.equals("null", true)) return ""
        return s.substringBefore(".0")
    }

    private fun isTruthy(raw: Any?): Boolean {
        when (raw) {
            null -> return false
            is Boolean -> return raw
            is Number -> return raw.toDouble() != 0.0
            else -> {
                val s = raw.toString().trim()
                return s == "1" || s.equals("true", true) || s.equals("yes", true)
            }
        }
    }

    fun formatXtreamDate(raw: Any?): String {
        val s = raw?.toString()?.trim().orEmpty()
        if (s.isEmpty() || s.equals("null", true)) return ""
        if (s.equals("Unlimited", true) || s.equals("Ilimitado", true)) return "Ilimitado"
        // Ya viene como fecha legible
        if (s.contains("-") || s.contains("/")) return s
        val epoch = s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
        if (epoch == null) return s
        if (epoch <= 0L) return "Ilimitado"
        return runCatching {
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                .format(Date(epoch * 1000L))
        }.getOrDefault(s)
    }
}
