package com.ichiro.pallyagent

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF080808)
private val Card = Color(0xFF1B1B1B)
private val Edge = Color(0xFF3A3A3A)
private val White = Color(0xFFF2F1F0)
private val Dim = Color(0xFFA09F9E)
private val Accent = Color(0xFFBDCEC8)
private enum class Page { CHAT, WORK, ASSISTANTS, MCP, SETTINGS, VOICE }
private enum class State { IDLE, THINKING, SEARCHING }
private data class Line(val id: String = UUID.randomUUID().toString(), val text: String, val outgoing: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        val motion = try { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f } catch (_: Exception) { true }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Card, onSurface = White)) { AgentApp(motion) } }
    }
}

@Composable private fun AgentApp(systemMotion: Boolean) {
    var page by remember { mutableStateOf(Page.CHAT) }
    var selected by remember { mutableStateOf("Pally") }
    var motion by remember { mutableStateOf(systemMotion) }
    var preview by remember { mutableStateOf(State.IDLE) }
    val messages = remember { mutableStateListOf<Line>() }
    var draft by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var showAttachments by remember { mutableStateOf(false) }
    BackHandler(page != Page.CHAT || showAttachments) { if (showAttachments) showAttachments = false else page = Page.CHAT }
    LaunchedEffect(preview) { if (preview != State.IDLE) { delay(4000); preview = State.IDLE } }
    Box(Modifier.fillMaxSize().background(Ink)) {
        Scanlines(Modifier.matchParentSize())
        when (page) {
            Page.CHAT -> Chat(selected, messages, draft, { draft = it }, preview, motion,
                onMenu = { page = Page.WORK }, onCall = { page = Page.VOICE },
                onBrand = { page = Page.ASSISTANTS }, onAdd = { showAttachments = true },
                onSend = { if (draft.isNotBlank()) { messages.add(Line(text = draft.trim(), outgoing = true)); draft = "" } })
            Page.WORK -> Work({ page = Page.CHAT }, { page = Page.ASSISTANTS }, { page = Page.SETTINGS })
            Page.ASSISTANTS -> Assistants(selected, { selected = it; page = Page.CHAT }, { page = Page.MCP }, { page = Page.CHAT })
            Page.MCP -> McpSetup(endpoint, { endpoint = it }, { page = Page.ASSISTANTS })
            Page.SETTINGS -> Preferences(motion, { motion = it }, { page = Page.CHAT }, { preview = it; page = Page.CHAT })
            Page.VOICE -> Voice(motion) { page = Page.CHAT }
        }
    }
    if (showAttachments) AlertDialog(onDismissRequest = { showAttachments = false }, title = { Text("Attachments") }, text = { Text("Attachments are not available in this preview.") }, confirmButton = { TextButton(onClick = { showAttachments = false }) { Text("OK") } })
}

@Composable private fun Scanlines(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .055f), Color.White.copy(alpha = .06f), Color.Transparent))
        val spacing = 5.dp.toPx()
        var y = 0f
        while (y < size.height) { drawLine(brush, Offset(0f, y), Offset(size.width, y), strokeWidth = .45.dp.toPx()); y += spacing }
    }
}

@Composable private fun CircleControl(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.size(48.dp), shape = CircleShape, color = Card.copy(alpha = .92f), border = BorderStroke(1.dp, Edge)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, modifier = Modifier.size(23.dp), tint = White) }
    }
}

@Composable private fun Brand(name: String, state: State, motion: Boolean, click: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "assistant motion")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "ring")
    val pulse by transition.animateFloat(.96f, 1.05f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(62.dp).clip(CircleShape).clickable(onClick = click).border(1.dp, Edge, CircleShape).semantics { contentDescription = "Selected assistant $name, open assistants" }, contentAlignment = Alignment.Center) {
        if (state != State.IDLE) Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = if (motion) angle else 0f }) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Accent, Color.Transparent)), style = Stroke(1.5.dp.toPx()))
        }
        if (name == "Pally") Image(painterResource(R.drawable.pally_logo), contentDescription = null, modifier = Modifier.size(45.dp).scale(if (motion && state != State.IDLE) pulse else 1f).clip(CircleShape))
        else Text(if (name == "Fo by Wajo") "Fo" else name.take(1), color = White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun TopBar(name: String, state: State, motion: Boolean, menu: () -> Unit, call: () -> Unit, brand: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CircleControl(Icons.Default.Menu, "Open your work", menu)
        Brand(name, state, motion, brand)
        CircleControl(Icons.Default.Call, "Voice", call)
    }
}

@Composable private fun Chat(name: String, messages: List<Line>, input: String, onInput: (String) -> Unit, state: State, motion: Boolean, onMenu: () -> Unit, onCall: () -> Unit, onBrand: () -> Unit, onAdd: () -> Unit, onSend: () -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(name, state, motion, onMenu, onCall, onBrand)
        Text(name + " · not connected", Modifier.fillMaxWidth(), color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("your conversation starts here", color = White, fontSize = 23.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text("Preview only. Messages stay on this screen and are not sent to an assistant.", color = Dim, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            }
        } else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.outgoing) Color(0xFF111111) else Card, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Edge), modifier = Modifier.widthIn(max = 310.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                            Text(message.text, color = White, fontSize = 16.sp, lineHeight = 23.sp)
                            Text("local only", Modifier.align(Alignment.End).padding(top = 6.dp), color = Dim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (state != State.IDLE) Text(if (state == State.THINKING) "thinking animation preview" else "searching animation preview", Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleControl(Icons.Default.Add, "Add attachment", onAdd)
            TextField(value = input, onValueChange = onInput, modifier = Modifier.weight(1f).heightIn(min = 48.dp), maxLines = 4, placeholder = { Text("message $name", color = Dim, fontSize = 15.sp) }, shape = RoundedCornerShape(27.dp), colors = TextFieldDefaults.colors(focusedContainerColor = Card, unfocusedContainerColor = Card, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Accent))
            CircleControl(Icons.AutoMirrored.Filled.Send, "Add local message", onSend)
        }
    }
}

@Composable private fun ScreenHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = White) }
        Text(title, Modifier.padding(start = 6.dp), color = White, fontSize = 24.sp)
    }
}

@Composable private fun RowCard(title: String, subtitle: String, click: () -> Unit) {
    Surface(onClick = click, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), color = Card, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Edge)) {
        Column(Modifier.padding(17.dp)) { Text(title, color = White, fontSize = 16.sp); Spacer(Modifier.height(3.dp)); Text(subtitle, color = Dim, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable private fun Work(back: () -> Unit, connections: () -> Unit, settings: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenHeader("your work", back)
        Text("No tasks are connected in this preview", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        RowCard("assistants & tools", "Choose an assistant or view MCP setup", connections)
        RowCard("preferences", "Motion and animation previews", settings)
    }
}

@Composable private fun Assistants(selected: String, choose: (String) -> Unit, mcp: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenHeader("assistants", back)
        Text("Choose the identity for this preview", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        listOf("Pally", "Fo by Wajo").forEach { name -> RowCard(name + if (name == selected) "  · selected" else "", "Preview identity only · not connected") { choose(name) } }
        Spacer(Modifier.height(20.dp))
        RowCard("Connect an MCP server", "View endpoint setup · connection is not implemented", mcp)
        Text("MCP servers offer tools; they are not necessarily chat assistants. Fo uses a text placeholder until a verified logo is available.", Modifier.padding(top = 18.dp), color = Dim, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable private fun McpSetup(endpoint: String, change: (String) -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenHeader("MCP server", back)
        Text("Endpoint preview", fontSize = 20.sp, color = White)
        Text("This form does not connect, authenticate or save. Do not enter keys or tokens.", Modifier.padding(vertical = 16.dp), color = Dim, fontSize = 13.sp, lineHeight = 19.sp)
        OutlinedTextField(value = endpoint, onValueChange = change, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTPS server URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(20.dp))
        Text("Pally and Fo require provider-specific authentication and capabilities. Generic MCP support is not yet implemented.", color = Dim, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable private fun Preferences(motion: Boolean, setMotion: (Boolean) -> Unit, back: () -> Unit, preview: (State) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenHeader("preferences", back)
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text("assistant animation", Modifier.weight(1f)); Switch(motion, setMotion) }
        RowCard("preview thinking", "Visual animation only") { preview(State.THINKING) }
        RowCard("preview searching", "Visual animation only") { preview(State.SEARCHING) }
        RowCard("Gemini Live setup", "Not yet implemented · do not enter an API key") { }
        Text("No voice, provider connection or agent reactions are implemented yet.", Modifier.padding(top = 18.dp), color = Dim, fontSize = 12.sp)
    }
}

@Composable private fun Voice(motion: Boolean, back: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voice orb")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "orb phase")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CircleControl(Icons.Default.Close, "Close voice", back) }
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(240.dp).semantics { contentDescription = "Animated voice orb preview" }) {
            val p = if (motion) progress else 0f
            val cx = size.width / 2; val cy = size.height / 2; val radius = size.minDimension * .36f
            val path = Path()
            for (i in 0..120) {
                val a = i * 2.0 * Math.PI / 120.0
                val r = radius * (1f + .055f * sin((a * 3.0 + p * 2.0 * Math.PI)).toFloat() + .027f * cos((a * 5.0 - p * 2.0 * Math.PI)).toFloat())
                val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, Brush.radialGradient(listOf(Color(0xFFD5E3E1), Color(0xFF647F87), Color(0xFF2B3941)), center = Offset(cx * .7f, cy * .7f), radius = radius * 2f))
            drawPath(path, Color.White.copy(alpha = .28f), style = Stroke(1.dp.toPx()))
        }
        Spacer(Modifier.height(32.dp))
        Text("voice preview", fontSize = 22.sp, color = White)
        Text("The orb is visual only. Gemini Live and microphone audio are not connected.", Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 19.sp, color = Dim, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("close") }
    }
}
