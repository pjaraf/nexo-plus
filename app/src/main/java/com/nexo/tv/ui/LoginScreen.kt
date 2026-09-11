package com.nexo.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.tv.R
import com.nexo.tv.Session
import com.nexo.tv.data.XtreamClient
import kotlinx.coroutines.launch

private val Ember = Color(0xFFFF6A1A)
private val EmberDeep = Color(0xFFDE5B17)
private val Ink = Color(0xFF07060A)
private val InkWarm = Color(0xFF1A0E08)
private val Panel = Color(0x66120C0A)

@Composable
fun LoginScreen(onSuccess: () -> Unit) {
    var user by remember { mutableStateOf(Session.username) }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val passFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { appeared = true }
    val formAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(700),
        label = "formAlpha"
    )
    val formLift by animateFloatAsState(
        targetValue = if (appeared) 0f else 28f,
        animationSpec = tween(700),
        label = "formLift"
    )

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            // Servidor: http://eliteplusec.com:8080
            val ok = XtreamClient.login(
                user.trim(),
                pass,
                preferredServer = Session.server.ifBlank { Session.SERVER }
            )
            busy = false
            if (ok) onSuccess() else error = "Usuario o clave incorrectos"
        }
    }

    Box(Modifier.fillMaxSize()) {
        LoginBackdrop()

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 36.dp)
                .alpha(formAlpha)
                .padding(top = formLift.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_nexo_logo),
                contentDescription = "NEXO",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(28.dp))
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "NEXO",
                color = Ember,
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Tu TV, al instante",
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(36.dp))

            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(22.dp))
                    .padding(horizontal = 28.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Iniciar sesión",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocus(shape = RoundedCornerShape(14.dp), focusedScale = 1.01f)
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passFocus)
                        .tvFocus(shape = RoundedCornerShape(14.dp), focusedScale = 1.01f)
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error!!, color = Color(0xFFFF8A80), fontSize = 14.sp)
                }
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = { submit() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmberDeep,
                        disabledContainerColor = EmberDeep.copy(alpha = 0.45f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .tvFocus(shape = RoundedCornerShape(14.dp), focusedScale = 1.03f)
                ) {
                    Text(
                        if (busy) "Entrando…" else "Entrar",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun LoginBackdrop() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(4200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val drift by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "drift"
    )
    val sweep by pulse.animateFloat(
        initialValue = -0.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(InkWarm, Ink, Color(0xFF040308)),
                startY = 0f,
                endY = h
            )
        )

        // Ember glow — esquina superior derecha
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Ember.copy(alpha = 0.42f * glow),
                    EmberDeep.copy(alpha = 0.18f * glow),
                    Color.Transparent
                ),
                center = Offset(w * (0.82f + drift * 0.04f), h * 0.18f),
                radius = w * 0.55f
            ),
            radius = w * 0.55f,
            center = Offset(w * (0.82f + drift * 0.04f), h * 0.18f)
        )

        // Glow cálido inferior izquierdo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF8A3D).copy(alpha = 0.28f * glow),
                    Color.Transparent
                ),
                center = Offset(w * (0.12f - drift * 0.03f), h * 0.78f),
                radius = w * 0.48f
            ),
            radius = w * 0.48f,
            center = Offset(w * (0.12f - drift * 0.03f), h * 0.78f)
        )

        // Franja diagonal de luz
        val beam = Path().apply {
            val x = w * sweep
            moveTo(x - w * 0.08f, 0f)
            lineTo(x + w * 0.02f, 0f)
            lineTo(x - w * 0.18f, h)
            lineTo(x - w * 0.28f, h)
            close()
        }
        drawPath(
            path = beam,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.06f),
                    Ember.copy(alpha = 0.10f),
                    Color.Transparent
                )
            )
        )

        // Anillos sutiles detrás del brand
        val ringCenter = Offset(w * 0.5f, h * 0.28f)
        for (i in 1..3) {
            drawCircle(
                color = Ember.copy(alpha = 0.07f * glow / i),
                radius = w * (0.12f + i * 0.07f),
                center = ringCenter,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Viñeta
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                center = Offset(w * 0.5f, h * 0.45f),
                radius = w * 0.85f
            )
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = Ember,
    unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
    focusedLabelColor = Ember,
    unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
    cursorColor = Ember,
    focusedContainerColor = Color.Black.copy(alpha = 0.18f),
    unfocusedContainerColor = Color.Black.copy(alpha = 0.10f)
)
