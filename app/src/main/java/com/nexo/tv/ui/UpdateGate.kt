package com.nexo.tv.ui

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexo.tv.update.AppUpdater
import com.nexo.tv.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateGate(enabled: Boolean = true) {
    if (!enabled) return
    val ctx = LocalContext.current
    val appCtx = remember(ctx) { ctx.applicationContext }
    val activity = ctx as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var starting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    /** Actualización pendiente de reanudar tras habilitar “apps desconocidas”. */
    var pendingAfterPermission by remember { mutableStateOf<UpdateInfo?>(null) }
    val updateFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        info = AppUpdater.check()
    }

    fun runDownloadAndInstall(pending: UpdateInfo) {
        if (starting) return
        scope.launch {
            starting = true
            info = null
            pendingAfterPermission = null
            Toast.makeText(appCtx, "Actualizando…", Toast.LENGTH_SHORT).show()
            val file = withContext(Dispatchers.IO) {
                AppUpdater.download(appCtx, pending) { }
            }
            if (file == null) {
                Toast.makeText(appCtx, "No se pudo descargar la actualización", Toast.LENGTH_LONG).show()
                info = pending
                starting = false
                status = "No se pudo descargar la actualización"
            } else {
                AppUpdater.install(appCtx, file)
                starting = false
            }
        }
    }

    fun startUpdateNow(target: UpdateInfo) {
        if (starting) return
        if (!AppUpdater.canInstallPackages(ctx)) {
            pendingAfterPermission = target
            status = "Activa “Instalar apps desconocidas” para NEXO"
            Toast.makeText(
                appCtx,
                "Activa Instalar apps desconocidas para NEXO y vuelve",
                Toast.LENGTH_LONG
            ).show()
            val opened = AppUpdater.openInstallPermission(activity ?: ctx)
            if (!opened) {
                status = "No se pudo abrir la configuración. Activa instalar apps desconocidas manualmente."
            }
            return
        }
        runDownloadAndInstall(target)
    }

    // Al volver de Ajustes, si ya otorgó el permiso, continúa la actualización sola.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val pending = pendingAfterPermission ?: return@LifecycleEventObserver
            if (AppUpdater.canInstallPackages(ctx)) {
                Toast.makeText(appCtx, "Permiso listo. Actualizando…", Toast.LENGTH_SHORT).show()
                runDownloadAndInstall(pending)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val update = info ?: return

    Dialog(
        onDismissRequest = {
            if (!update.mandatory && !starting && pendingAfterPermission == null) info = null
        },
        properties = DialogProperties(
            dismissOnBackPress = !update.mandatory,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        LaunchedEffect(Unit) {
            runCatching { updateFocus.requestFocus() }
        }
        Column(
            Modifier
                .width(420.dp)
                .background(Color(0xFF161616), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text(
                "Actualización disponible",
                color = Color(0xFFFF6A1A),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Nueva versión ${update.versionName}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (update.changelog.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(update.changelog, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!update.mandatory && !starting) {
                    TextButton(
                        onClick = {
                            pendingAfterPermission = null
                            info = null
                        }
                    ) {
                        Text("Después", color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    enabled = !starting,
                    onClick = { startUpdateNow(update) },
                    modifier = Modifier
                        .focusRequester(updateFocus)
                        .focusable(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDE5B17))
                ) {
                    Text(
                        if (pendingAfterPermission != null) "Abrir permiso" else "Actualizar",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
