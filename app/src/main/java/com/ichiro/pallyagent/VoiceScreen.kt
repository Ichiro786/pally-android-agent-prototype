package com.ichiro.pallyagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gemini Live voice call. The orb reacts to listening, connecting and speaking. The call is
 * stopped when this screen leaves composition, so the microphone is never left running.
 */
@Composable
internal fun PallyVoiceScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    saved: Boolean,
    onSetup: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf("ready") }
    var transcript by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<GeminiLiveSession?>(null) }
    // Each call gets an id, so late events from an earlier call cannot change the current one.
    var callId by remember { mutableIntStateOf(0) }
    val transition = rememberInfiniteTransition(label = "voice-orb")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), label = "orb")

    fun startWithKey(key: String) {
        if (key.isBlank()) {
            status = "add a Gemini API key in voice setup"
            onSetup()
            return
        }
        transcript = ""
        status = "connecting…"
        val id = callId + 1
        callId = id
        val live = GeminiLiveSession(key, object : GeminiLiveSession.Listener {
            override fun onState(value: String) {
                main.post {
                    if (callId != id) return@post
                    status = value
                    if (value == "call ended") session = null
                }
            }

            override fun onTranscript(text: String, fromModel: Boolean) {
                main.post {
                    if (callId != id) return@post
                    transcript = (transcript + if (transcript.isBlank()) "" else "\n" + if (fromModel) "Gemini: " else "you: ") + text
                }
            }

            override fun onError(message: String) {
                main.post {
                    if (callId != id) return@post
                    status = "call ended"
                    transcript = message
                    session = null
                }
            }
        })
        session = live
        runCatching { live.start() }.onFailure {
            session = null
            status = it.message ?: "Gemini Live could not start."
        }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWithKey(SecretVault.read(context, GEMINI_KEY).orEmpty()) else status = "microphone permission denied"
    }

    fun toggleCall() {
        val current = session
        if (current != null) {
            current.stop()
            session = null
            status = "call ended"
            return
        }
        if (!saved) {
            status = "add a Gemini API key in voice setup"
            onSetup()
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mic.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startWithKey(SecretVault.read(context, GEMINI_KEY).orEmpty())
        }
    }

    // Keyed on Unit: the cleanup reads the session current at exit, so leaving the screen ends the call.
    DisposableEffect(Unit) {
        onDispose { session?.stop() }
    }

    val active = status == "listening" || status == "speaking" || status == "connecting…"
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close voice",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = {
            PallyPill(text = if (session != null) "end voice call" else "start voice call", onClick = { toggleCall() })
        }
    ) {
        PageTitle("Gemini Live", "Audio goes straight to Google. No MCP tools run in this call.")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSetup) { Text("voice setup", color = PallyColor.Accent) }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(250.dp).semantics { contentDescription = "Animated Gemini Live voice orb" }) {
                val p = if (motion) phase else 0f
                val cx = size.width / 2f
                val cy = size.height / 2f
                val base = size.minDimension * .36f
                val extra = if (active) .045f else .015f
                val shape = Path()
                for (i in 0..180) {
                    val angle = i * 2.0 * Math.PI / 180.0
                    val radius = base * (1f + (.07f + extra) * sin(angle * 3 + p * 6.28f).toFloat() + .04f * cos(angle * 5 - p * 6.28f).toFloat())
                    val x = cx + radius * cos(angle).toFloat()
                    val y = cy + radius * sin(angle).toFloat()
                    if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                }
                shape.close()
                drawCircle(Color(0xFF344047).copy(alpha = if (active) .68f else .50f), base * if (active) 1.32f else 1.25f, Offset(cx, cy))
                drawPath(shape, Brush.radialGradient(listOf(Color(0xFFEAF3EE), Color(0xFF9DB8B9), Color(0xFF536E79), Color(0xFF26323A)), Offset(cx * .72f, cy * .70f), base * 2f))
                drawPath(shape, Color.White.copy(alpha = .42f), style = Stroke(1.2.dp.toPx()))
            }
            Text(
                if (saved) status else "add a Gemini API key in voice setup",
                Modifier.padding(top = 10.dp),
                color = PallyColor.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            if (transcript.isNotBlank()) {
                Text(
                    transcript,
                    Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(max = 120.dp),
                    color = PallyColor.Text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 6
                )
            }
        }
    }
}
