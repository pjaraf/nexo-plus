package com.nexo.tv.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

private enum class UpdatePhase {
    Prompt,
    NeedPermission,
    Downloading,
    Installing,
    Error
}

@Composable
fun UpdateGate(enabled: Boolean = true) {
    if (!enabled) return
    val ctx = LocalContext.current
    val appCtx = remember(ctx) { ctx.applicationContext }
    val activity = ctx as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var phase by remember { mutableStateOf(UpdatePhase.Prompt) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingAfterPermission by remember { mutableStateOf<UpdateInfo?>(null) }
    val updateFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        update = AppUpdater.check()
        phase = UpdatePhase.Prompt
    }

    fun runDownloadAndInstall(pending: UpdateInfo) {
        if (busy) return
        val main = Handler(Looper.getMainLooper())
        scope.launch {
            busy = true
            pendingAfterPermission = null
            phase = UpdatePhase.Downloading
            progress = 0
            status = "Descargando actualización…"
            val file = withContext(Dispatchers.IO) {
                AppUpdater.download(appCtx, pending) { pct ->
                    main.post {
                        if (pct < 0) {
                            progress = 0
                            status = "Descargando actualización…"
                        } else {
                            progress = pct
                            status = "Descargando… $pct%"
                        }
                    }
                }
            }
            if (file == null) {
                phase = UpdatePhase.Error
                status = "No se pudo descargar. Revisá la conexión e intentá de nuevo."
                busy = false
                return@launch
            }
            phase = UpdatePhase.Installing
            progress = 100
            status = "Instalando… confirmá en la siguiente pantalla"
            try {
                AppUpdater.install(appCtx, file)
            } catch (t: Throwable) {
                phase = UpdatePhase.Error
                status = "No se pudo abrir el instalador: ${t.message ?: "error"}"
                busy = false
                return@launch
            }
            busy = false
        }
    }

    fun startUpdateNow(target: UpdateInfo) {
        if (busy) return
        if (!AppUpdater.canInstallPackages(ctx)) {
            pendingAfterPermission = target
            phase = UpdatePhase.NeedPermission
            status = "Activá “Instalar apps desconocidas” para NEXO y volvé. Se reanuda solo."
            Toast.makeText(
                appCtx,
                "Activá Instalar apps desconocidas para NEXO",
                Toast.LENGTH_LONG
            ).show()
            val opened = AppUpdater.openInstallPermission(activity ?: ctx)
            if (!opened) {
                status =
                    "No se pudo abrir Ajustes. Buscá Instalar apps desconocidas → NEXO → Permitir."
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
                status = "Permiso listo. Descargando…"
                runDownloadAndInstall(pending)
            } else {
                phase = UpdatePhase.NeedPermission
                status = "Todavía falta permitir Instalar apps desconocidas para NEXO."
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val current = update ?: return

    val canDismiss =
        !current.mandatory &&
            phase != UpdatePhase.Downloading &&
            phase != UpdatePhase.Installing &&
            !busy

    Dialog(
        onDismissRequest = {
            if (canDismiss) {
                pendingAfterPermission = null
                update = null
                phase = UpdatePhase.Prompt
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        LaunchedEffect(phase) {
            runCatching { updateFocus.requestFocus() }
        }
        Column(
            Modifier
                .width(440.dp)
                .background(Color(0xFF161616), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text(
                when (phase) {
                    UpdatePhase.NeedPermission -> "Permiso requerido"
                    UpdatePhase.Downloading -> "Descargando"
                    UpdatePhase.Installing -> "Instalando"
                    UpdatePhase.Error -> "Error al actualizar"
                    else -> "Actualización disponible"
                },
                color = Color(0xFFFF6A1A),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Nueva versión ${current.versionName}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (current.changelog.isNotBlank() && phase == UpdatePhase.Prompt) {
                Spacer(Modifier.height(8.dp))
                Text(current.changelog, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
            status?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Color(0xFFFFCC80), fontSize = 14.sp)
            }

            if (phase == UpdatePhase.Downloading || phase == UpdatePhase.Installing) {
                Spacer(Modifier.height(16.dp))
                if (phase == UpdatePhase.Downloading && progress <= 0) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = Color(0xFFDE5B17),
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { (progress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = Color(0xFFDE5B17),
                        trackColor = Color.White.copy(alpha = 0.15f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (phase == UpdatePhase.Downloading && progress <= 0) "Preparando…" else "$progress%",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDismiss) {
                    TextButton(
                        onClick = {
                            pendingAfterPermission = null
                            update = null
                            phase = UpdatePhase.Prompt
                            status = null
                        }
                    ) {
                        Text("Después", color = Color.Gray)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                when (phase) {
                    UpdatePhase.Downloading, UpdatePhase.Installing -> {
                        Text(
                            if (phase == UpdatePhase.Downloading) "Descargando…" else "Instalando…",
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    else -> {
                        Button(
                            enabled = !busy,
                            onClick = {
                                if (phase == UpdatePhase.NeedPermission) {
                                    AppUpdater.openInstallPermission(activity ?: ctx)
                                } else {
                                    startUpdateNow(current)
                                }
                            },
                            modifier = Modifier
                                .focusRequester(updateFocus)
                                .focusable(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDE5B17))
                        ) {
                            Text(
                                when (phase) {
                                    UpdatePhase.NeedPermission -> "Abrir permiso"
                                    UpdatePhase.Error -> "Reintentar"
                                    else -> "Actualizar"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
