package com.nexo.tv.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Collections
import java.util.WeakHashMap

private val immersiveByActivity =
    Collections.synchronizedMap(WeakHashMap<Activity, Boolean>())

/**
 * Telefono/tablet: barra de estado del mismo color que el header NEXO.
 * TV Box no se toca.
 */
fun Activity.applyPhoneTabletCleanSystemBars() {
    if (Device.isTv(this)) return
    immersiveByActivity[this] = false
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    val bar = Color.parseColor("#131418")
    window.statusBarColor = bar
    window.navigationBarColor = bar
    if (Build.VERSION.SDK_INT >= 29) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    controller.show(WindowInsetsCompat.Type.statusBars())
    controller.show(WindowInsetsCompat.Type.navigationBars())
}

/**
 * Telefono/tablet: oculta hora/senal/bateria (y nav) en pantalla completa.
 * Se re-aplica en focus/config porque Samsung One UI a veces restaura las barras al rotar.
 */
fun Activity.setPhoneTabletPlayerFullscreen(fullscreen: Boolean) {
    if (Device.isTv(this)) return
    immersiveByActivity[this] = fullscreen
    if (!fullscreen) {
        applyPhoneTabletCleanSystemBars()
        return
    }
    applyImmersiveNow()
    // Tras rotar a landscape One UI puede volver a mostrar la barra; reaplicar.
    window.decorView.post { if (immersiveByActivity[this] == true) applyImmersiveNow() }
    window.decorView.postDelayed({ if (immersiveByActivity[this] == true) applyImmersiveNow() }, 120)
    window.decorView.postDelayed({ if (immersiveByActivity[this] == true) applyImmersiveNow() }, 400)
}

/** True si Live/Movie/Series pidio immersive (para onWindowFocusChanged / config). */
val Activity.playerImmersiveRequested: Boolean
    get() = immersiveByActivity[this] == true

private fun Activity.applyImmersiveNow() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    // Sin DRAWS_SYSTEM_BAR_BACKGROUNDS Samsung a veces deja iconos encima del video.
    window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    if (Build.VERSION.SDK_INT >= 29) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
    if (Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.let { ic ->
            ic.hide(
                android.view.WindowInsets.Type.statusBars()
                    or android.view.WindowInsets.Type.navigationBars()
            )
            ic.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
