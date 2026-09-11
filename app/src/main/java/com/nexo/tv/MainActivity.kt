package com.nexo.tv

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nexo.tv.data.Catalog
import com.nexo.tv.data.XtreamClient
import com.nexo.tv.ui.Device
import com.nexo.tv.ui.HubScreen
import com.nexo.tv.ui.LoginScreen
import com.nexo.tv.ui.SplashScreen
import com.nexo.tv.ui.UpdateGate
import com.nexo.tv.AppExit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent

private enum class AppScreen { Loading, Login, Hub }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        keepAwakeWhileVisible()

        val forceLogin = intent.getBooleanExtra(EXTRA_FORCE_LOGIN, false)
        if (forceLogin) {
            Catalog.clear()
            Session.logout()
        }

        val bootUser = intent.getStringExtra("user")
        val bootPass = intent.getStringExtra("pass")
        val bootServer = intent.getStringExtra("server")
        val hasBoot = !forceLogin && !bootUser.isNullOrBlank() && !bootPass.isNullOrBlank()
        val start = if (!forceLogin && (hasBoot || Session.isLoggedIn)) {
            AppScreen.Loading
        } else {
            AppScreen.Login
        }

        setContent {
            var screen by remember { mutableStateOf(start) }
            var lastBackAt by remember { mutableLongStateOf(0L) }

            LaunchedEffect(screen) {
                if (screen != AppScreen.Loading) return@LaunchedEffect
                val ok = withContext(Dispatchers.IO) {
                    when {
                        Session.isLoggedIn -> XtreamClient.login(
                            Session.username,
                            Session.password,
                            preferredServer = Session.server
                        )
                        hasBoot -> XtreamClient.login(
                            bootUser!!.trim(),
                            bootPass!!,
                            preferredServer = bootServer ?: Session.server
                        )
                        else -> false
                    }
                }
                if (!ok) {
                    Catalog.clear()
                    screen = AppScreen.Login
                    return@LaunchedEffect
                }
                // Telefono / tablet: abrir TV al instante (catalogo se precarga en background).
                // TV Box: esperar catalogo antes del Hub.
                if (!Device.isTv(this@MainActivity)) {
                    Catalog.preloadAsync(this@MainActivity)
                    AppExit.suppressHomeExit = true
                    startActivity(
                        Intent(this@MainActivity, LiveActivity::class.java)
                            .putExtra(LiveActivity.EXTRA_USER, Session.username)
                            .putExtra(LiveActivity.EXTRA_PASS, Session.password)
                            .putExtra(LiveActivity.EXTRA_SERVER, Session.server)
                    )
                    finish()
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { Catalog.preload(this@MainActivity) }
                    }
                    screen = AppScreen.Hub
                }
            }

            BackHandler(enabled = screen == AppScreen.Hub || screen == AppScreen.Login) {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000L) {
                    exitNexoCompletely()
                } else {
                    lastBackAt = now
                    Toast.makeText(
                        this@MainActivity,
                        "Pulsa atrás otra vez para salir",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            Box(Modifier.fillMaxSize()) {
                when (screen) {
                    AppScreen.Loading -> SplashScreen()
                    AppScreen.Login -> LoginScreen(onSuccess = { screen = AppScreen.Loading })
                    AppScreen.Hub -> HubScreen(
                        onLogout = {
                            Catalog.clear()
                            Session.logout()
                            screen = AppScreen.Login
                        }
                    )
                }
                if (screen == AppScreen.Login || screen == AppScreen.Hub) {
                    UpdateGate()
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (AppExit.suppressHomeExit) {
            AppExit.suppressHomeExit = false
            return
        }
        exitNexoCompletely()
    }

    override fun onResume() {
        super.onResume()
        AppExit.suppressHomeExit = false
    }

    companion object {
        const val EXTRA_FORCE_LOGIN = "force_login"
    }
}
