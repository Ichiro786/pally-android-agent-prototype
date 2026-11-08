package com.ichiro.pallyagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF080808)
private val Bubble = Color(0xFF222120)
private val Edge = Color(0xFF484644)
private val White = Color(0xFFF4F2F0)
private val Muted = Color(0xFFAAA7A4)
private val Accent = Color(0xFFB8D5D0)
private const val FoEndpoint = "https://mcp.api.wajo.ai/fo"
private const val PallyEndpoint = "https://agent.pally.com/mcp"
private const val FoSecret = "wajo_mcp_api_key"
private const val GeminiSecret = "gemini_live_key"

private enum class Screen { CHAT, WORK, ASSISTANTS, MCP, SETTINGS, VOICE }
private enum class AgentMode { IDLE, THINKING, SEARCHING }
private data class ChatLine(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fromAssistant: Boolean,
    val time: String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()),
    val quote: String? = null,
    val sample: Boolean = false,
    val localOnly: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        val motion = try { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f } catch (_: Exception) { true }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Bubble, onSurface = White)) { PallyApp(motion) } }
    }
}

@Composable private fun PallyApp(systemMotion: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(Screen.CHAT) }
    var assistant by remember { mutableStateOf("Pally") }
    var motion by remember { mutableStateOf(systemMotion) }
    var mode by remember { mutableStateOf(AgentMode.IDLE) }
    var draft by remember { mutableStateOf("") }
    var replyQuote by remember { mutableStateOf<String?>(null) }
    var showAttachments by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var workDrawer by remember { mutableStateOf(false) }
    var pendingNote by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(FoEndpoint) }
    var apiKey by remember { mutableStateOf("") }
    var authHeader by remember { mutableStateOf("X-API-Key") }
    var status by remember { mutableStateOf("not connected") }
    var connectionError by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<McpClient?>(null) }
    var clientAssistant by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var savedGeminiKey by remember { mutableStateOf(SecretVault.read(context, GeminiSecret) != null) }
    var sampleShown by remember { mutableStateOf(true) }
    val lines = remember { mutableStateListOf<ChatLine>() }
    val connected = client != null && clientAssistant == assistant

    LaunchedEffect(Unit) {
        if (lines.isEmpty()) {
            lines += ChatLine(text = "i can help you think it through. what matters most right now?", fromAssistant = true, time = "1:40 am", sample = true)
            lines += ChatLine(text = "compare a few options for this weekend", fromAssistant = false, time = "1:41 am", quote = "you: can you help me choose?", sample = true)
            lines += ChatLine(text = "sure. tell me what you want to optimize for, and i'll keep the trade-offs clear.", fromAssistant = true, time = "1:41 am", sample = true)
            lines += ChatLine(text = "keep it simple, and call out the biggest difference", fromAssistant = false, time = "1:42 am", sample = true)
            lines += ChatLine(text = "got it. i'll keep the comparison short and easy to scan.", fromAssistant = true, time = "1:42 am", sample = true)
        }
    }
    BackHandler(screen != Screen.CHAT || workDrawer || showAttachments || showEmoji) {
        when { showAttachments -> showAttachments = false; showEmoji -> showEmoji = false; workDrawer -> workDrawer = false; else -> screen = Screen.CHAT }
    }

    fun connect() {
        if (assistant == "Pally") {
            status = "sign-in required"
            connectionError = "Pally's MCP uses OAuth 2.1 with PKCE. This preview does not finish that sign-in flow, so Pally cannot connect here yet."
            return
        }
        val target = if (assistant == "Fo by Wajo") FoEndpoint else endpoint.trim()
        val secret = apiKey.trim().ifBlank { SecretVault.read(context, FoSecret).orEmpty() }
        if (!target.startsWith("https://")) { connectionError = "Enter an HTTPS MCP endpoint."; return }
        if (assistant == "Fo by Wajo" && !secret.startsWith("wak_")) { connectionError = "Enter your Wajo API key in the app. It should start with wak_. Do not send it in chat."; return }
        connectionError = ""
        status = "connecting…"
        busy = true
        scope.launch {
            try {
                val opened = McpClient(target, secret, authHeader)
                val available = opened.connectAndListTools()
                if (assistant == "Fo by Wajo" && available.none { it.name == "ask_fo" }) throw IllegalStateException("The endpoint connected but did not advertise ask_fo, so it is not the Fo profile.")
                client = opened
                clientAssistant = assistant
                tools = available
                status = "connected · ${available.size} tools"
                if (assistant == "Fo by Wajo") SecretVault.save(context, FoSecret, secret)
                apiKey = ""
            } catch (e: Exception) {
                client = null; clientAssistant = ""; tools = emptyList()
                status = "connection failed"
                connectionError = e.message ?: "Could not connect to this MCP server."
            } finally { busy = false }
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isBlank() || busy) return
        if (sampleShown) { lines.clear(); sampleShown = false }
        draft = ""
        val quote = replyQuote
        replyQuote = null
        if (assistant == "Fo by Wajo" && connected) {
            lines += ChatLine(text = text, fromAssistant = false, quote = quote)
            busy = true; mode = AgentMode.THINKING
            scope.launch {
                try {
                    val current = client ?: error("Reconnect to Fo first.")
                    val answer = FoAdapter.send(current, tools, text)
                    lines += ChatLine(text = answer, fromAssistant = true)
                } catch (e: Exception) {
                    lines += ChatLine(text = "I couldn't get a reply from Fo: ${e.message ?: "connection error"}", fromAssistant = true)
                } finally { busy = false; mode = AgentMode.IDLE }
            }
        } else {
            lines += ChatLine(text = text, fromAssistant = false, quote = quote, localOnly = true)
            pendingNote = if (assistant == "Pally") "This message stayed on this device. Pally sign-in isn't available in this preview." else "This message stayed on this device. Connect Fo by Wajo to get a live reply."
        }
    }

    fun disconnect() {
        client = null; clientAssistant = ""; tools = emptyList()
        SecretVault.clear(context, FoSecret)
        status = "disconnected"; connectionError = "Saved Wajo API key erased from this device."
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        ScanlineBackdrop(Modifier.matchParentSize())
        when (screen) {
            Screen.CHAT -> ChatScreen(assistant, lines, draft, { draft = it }, connected, busy, mode, motion,
                pendingNote, onMenu = { workDrawer = true }, onCall = { screen = Screen.VOICE },
                onLogo = { screen = Screen.ASSISTANTS }, onAdd = { showAttachments = true },
                onEmoji = { showEmoji = true }, onMic = { screen = Screen.VOICE }, onSend = { send() },
                onReply = { replyQuote = if (it.fromAssistant) "${assistant}: ${it.text}" else "you: ${it.text}" },
                onNoteDismiss = { pendingNote = "" })
            Screen.WORK -> WorkScreen({ screen = Screen.CHAT }, { screen = Screen.ASSISTANTS }, { screen = Screen.SETTINGS }, { screen = Screen.MCP })
            Screen.ASSISTANTS -> AssistantScreen(assistant, clientAssistant, { chosen -> assistant = chosen; screen = Screen.MCP; connectionError = ""; if (chosen == "Fo by Wajo") { endpoint = FoEndpoint; authHeader = "X-API-Key" } }, { screen = Screen.CHAT })
            Screen.MCP -> ConnectionScreen(assistant, endpoint, { endpoint = it }, apiKey, { apiKey = it }, authHeader, { authHeader = it }, status, connectionError, tools, busy, { connect() }, { disconnect() }, { name ->
                val url = if (name == "Pally") PallyEndpoint else "https://wajo.ai/docs/mcp-server.md"
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }, { screen = Screen.ASSISTANTS })
            Screen.SETTINGS -> SettingsScreen(motion, { motion = it }, savedGeminiKey,
                { value -> SecretVault.save(context, GeminiSecret, value); savedGeminiKey = value.isNotBlank() },
                { SecretVault.clear(context, GeminiSecret); savedGeminiKey = false },
                { screen = Screen.CHAT }, { mode = it; screen = Screen.CHAT })
            Screen.VOICE -> VoiceScreen(motion, savedGeminiKey, { screen = Screen.SETTINGS }, { screen = Screen.CHAT })
        }
        if (screen == Screen.CHAT && workDrawer) WorkDrawer(
            onClose = { workDrawer = false }, onConnections = { workDrawer = false; screen = Screen.ASSISTANTS },
            onSettings = { workDrawer = false; screen = Screen.SETTINGS }, onMcp = { workDrawer = false; screen = Screen.MCP }
        )
        if (screen == Screen.CHAT && showAttachments) AttachmentSheet(onDismiss = { showAttachments = false }, onPick = { item ->
            showAttachments = false; pendingNote = "$item selection is shown in the mockup; this provider adapter can't send attachments yet."
        })
        if (screen == Screen.CHAT && showEmoji) EmojiSheet(onDismiss = { showEmoji = false }, onPick = { emoji -> draft += emoji; showEmoji = false })
    }
}

@Composable private fun ScanlineBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val fade = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .018f), Color.White.copy(alpha = .028f), Color.White.copy(alpha = .018f), Color.Transparent))
        val gap = 5.dp.toPx(); var y = 0f
        while (y < size.height) { drawLine(fade, Offset(0f, y), Offset(size.width, y), strokeWidth = .55.dp.toPx()); y += gap }
    }
}

@Composable private fun CircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: Int = 52) {
    Surface(onClick = onClick, modifier = Modifier.size(size.dp).semantics { contentDescription = label }, shape = CircleShape,
        color = Color(0xFF151515).copy(alpha = .92f), border = BorderStroke(1.dp, Edge)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = White, modifier = Modifier.size(24.dp)) }
    }
}

@Composable private fun AssistantMark(name: String, mode: AgentMode, motion: Boolean, onClick: () -> Unit, size: Int = 72) {
    val transition = rememberInfiniteTransition(label = "assistant mark")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(6500, easing = LinearEasing)), label = "orbit")
    val pulse by transition.animateFloat(.96f, 1.045f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(size.dp).clip(CircleShape).clickable(onClick = onClick).border(1.dp, Edge, CircleShape).semantics { contentDescription = "$name assistant, select assistant" }, contentAlignment = Alignment.Center) {
        if (mode != AgentMode.IDLE && motion) Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation }) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Accent.copy(alpha = .85f), Color.Transparent)), style = Stroke(1.5.dp.toPx()))
        }
        if (name == "Pally") Image(painterResource(R.drawable.pally_logo), "Pally logo", Modifier.size((size - 20).dp).scale(if (motion && mode != AgentMode.IDLE) pulse else 1f).clip(CircleShape))
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (name == "Fo by Wajo") "Fo" else "MCP", color = White, fontSize = if (name == "Fo by Wajo") 18.sp else 13.sp, fontWeight = FontWeight.Medium)
            if (name == "Fo by Wajo") Text("by Wajo", color = Muted, fontSize = 8.sp)
        }
    }
}

@Composable private fun TopBar(name: String, mode: AgentMode, motion: Boolean, menu: () -> Unit, call: () -> Unit, logo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CircleButton(Icons.Default.Menu, "Open your work", menu)
        AssistantMark(name, mode, motion, logo)
        CircleButton(Icons.Default.Call, "Open voice", call)
    }
}

@Composable private fun DayDivider(text: String = "today") {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Edge.copy(alpha = .50f))
        Text(text, Modifier.padding(horizontal = 16.dp), color = Muted, fontSize = 13.sp)
        HorizontalDivider(Modifier.weight(1f), color = Edge.copy(alpha = .50f))
    }
}

@Composable private fun ChatScreen(
    name: String, lines: List<ChatLine>, input: String, onInput: (String) -> Unit, connected: Boolean,
    busy: Boolean, mode: AgentMode, motion: Boolean, note: String, onMenu: () -> Unit,
    onCall: () -> Unit, onLogo: () -> Unit, onAdd: () -> Unit, onEmoji: () -> Unit,
    onMic: () -> Unit, onSend: () -> Unit, onReply: (ChatLine) -> Unit, onNoteDismiss: () -> Unit
) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(name, mode, motion, onMenu, onCall, onLogo)
        DayDivider()
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("your conversation starts here", color = White, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 19.dp, end = 19.dp, top = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                val demo = lines.any { it.sample }
                if (demo) item { Text("sample preview · not connected", Modifier.fillMaxWidth().padding(bottom = 1.dp), color = Muted.copy(alpha = .72f), fontSize = 10.sp, textAlign = TextAlign.Center) }
                items(lines, key = { it.id }) { line -> ChatBubble(line, name, onReply) }
                if (busy) item {
                    Row(Modifier.fillMaxWidth().padding(start = 5.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistantMark(name, mode, motion, {}, size = 30)
                        Text(if (mode == AgentMode.SEARCHING) "  searching" else "  ${name} is thinking", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        if (note.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = Color(0xFF171717), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Edge)) {
            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note, Modifier.weight(1f), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                IconButton(onClick = onNoteDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Dismiss", tint = Muted, modifier = Modifier.size(16.dp)) }
            }
        }
        Composer(name, input, onInput, onAdd, onEmoji, onMic, onSend)
    }
}

@Composable private fun ChatBubble(line: ChatLine, assistant: String, onReply: (ChatLine) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromAssistant) Arrangement.Start else Arrangement.End) {
        Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(.88f)) {
            if (line.fromAssistant) Row(Modifier.padding(start = 5.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistantMark(assistant, AgentMode.IDLE, false, {}, size = 24)
                Text("  $assistant", color = Muted, fontSize = 12.sp)
            }
            Surface(
                modifier = Modifier.pointerInput(line.id) { detectTapGestures(onLongPress = { onReply(line) }) },
                color = if (line.fromAssistant) Bubble else Color(0xFF111111),
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (line.fromAssistant) 5.dp else 22.dp, bottomEnd = if (line.fromAssistant) 22.dp else 5.dp),
                border = if (line.fromAssistant) null else BorderStroke(1.dp, Edge.copy(alpha = .75f))
            ) {
                Column(Modifier.padding(start = 15.dp, end = 14.dp, top = 13.dp, bottom = 8.dp)) {
                    if (line.quote != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF3B3A39)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(31.dp).background(White, RoundedCornerShape(2.dp)))
                        Text(line.quote, Modifier.padding(start = 8.dp), color = Color(0xFFD1CFCD), fontSize = 13.sp, maxLines = 2, lineHeight = 17.sp)
                    }
                    Text(line.text, Modifier.padding(top = if (line.quote != null) 8.dp else 0.dp), color = White, fontSize = 16.sp, lineHeight = 23.sp)
                    Row(Modifier.align(Alignment.End).padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (line.localOnly) Text("local · not sent   ", color = Muted, fontSize = 9.sp)
                        Text(line.time, color = Muted, fontSize = 10.sp)
                        if (!line.fromAssistant && !line.localOnly) {
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Default.DoneAll, "Sent", tint = Color(0xFF90C7DF), modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Composer(name: String, value: String, onValue: (String) -> Unit, onAdd: () -> Unit, onEmoji: () -> Unit, onMic: () -> Unit, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        CircleButton(Icons.Default.Add, "Add attachment", onAdd, size = 54)
        Surface(Modifier.weight(1f).heightIn(min = 54.dp), shape = RoundedCornerShape(30.dp), color = Color(0xFF1C1B1A), border = BorderStroke(1.dp, Edge)) {
            Row(Modifier.padding(start = 17.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = value, onValueChange = onValue, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(color = White, fontSize = 16.sp, lineHeight = 21.sp), maxLines = 4, keyboardOptions = KeyboardOptions.Default, decorationBox = { inner ->
                    if (value.isEmpty()) Text("message $name", color = Muted, fontSize = 15.sp)
                    inner()
                })
                IconButton(onClick = onEmoji, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.EmojiEmotions, "Choose emoji", tint = White, modifier = Modifier.size(22.dp)) }
            }
        }
        CircleButton(if (value.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send, if (value.isBlank()) "Voice preview" else "Send message", if (value.isBlank()) onMic else onSend, size = 54)
    }
}

@Composable private fun ScreenTop(name: String, title: String, back: () -> Unit, call: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CircleButton(Icons.Default.Menu, "Back to your work", back)
        AssistantMark(name, AgentMode.IDLE, false, {}, size = 62)
        CircleButton(Icons.Default.Close, "Close $title", call, size = 52)
    }
    DayDivider()
    Text(title, Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), color = White, fontSize = 25.sp, fontWeight = FontWeight.Normal)
}

@Composable private fun WorkScreen(back: () -> Unit, assistants: () -> Unit, settings: () -> Unit, mcp: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTop("Pally", "our work", back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Text("in progress", color = Muted, fontSize = 12.sp)
            Text("nothing underway yet", Modifier.padding(top = 12.dp, bottom = 24.dp), color = Muted, fontSize = 14.sp)
            HorizontalDivider(color = Edge.copy(alpha = .5f))
            Text("connect", Modifier.padding(top = 22.dp, bottom = 10.dp), color = Muted, fontSize = 12.sp)
            MenuRow(Icons.Default.Hub, "assistants & tools", "manage assistant connections", assistants)
            MenuRow(Icons.Default.Storage, "MCP server", "add a trusted server", mcp)
            Spacer(Modifier.weight(1f))
        }
        MenuRow(Icons.Default.Person, "your space", "settings and profile", settings, Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
    }
}

@Composable private fun WorkDrawer(onClose: () -> Unit, onConnections: () -> Unit, onSettings: () -> Unit, onMcp: () -> Unit) {
    Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f))) {
        Column(Modifier.fillMaxHeight().fillMaxWidth(.78f).background(Color(0xFF0B0B0B)).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("our work", color = White, fontSize = 24.sp)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close drawer", tint = White) }
            }
            HorizontalDivider(color = Edge)
            Text("in progress", Modifier.padding(top = 24.dp, bottom = 14.dp), color = Muted, fontSize = 12.sp)
            Text("no active work", color = Muted, fontSize = 14.sp)
            HorizontalDivider(Modifier.padding(top = 24.dp), color = Edge)
            Text("connect", Modifier.padding(top = 22.dp, bottom = 12.dp), color = Muted, fontSize = 12.sp)
            MenuRow(Icons.Default.Hub, "assistants & tools", "manage connections", onConnections)
            MenuRow(Icons.Default.Storage, "MCP server", "connect a trusted endpoint", onMcp)
            Spacer(Modifier.weight(1f))
            MenuRow(Icons.Default.Person, "your space", "settings and profile", onSettings, Modifier.padding(bottom = 20.dp))
        }
        Box(Modifier.fillMaxSize().clickable(onClick = onClose))
    }
}

@Composable private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().padding(vertical = 5.dp), color = Color.Transparent) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Muted, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, color = White, fontSize = 15.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable private fun AssistantScreen(selected: String, connectedAssistant: String, choose: (String) -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTop(selected, "assistants", back)
        Text("choose the assistant for this conversation", Modifier.padding(horizontal = 22.dp, vertical = 4.dp), color = Muted, fontSize = 13.sp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            AssistantCard("Pally", "Pally", selected == "Pally", connectedAssistant == "Pally", "official assistant · OAuth sign-in required") { choose("Pally") }
            AssistantCard("Fo by Wajo", "Fo", selected == "Fo by Wajo", connectedAssistant == "Fo by Wajo", "Wajo's single-assistant MCP profile") { choose("Fo by Wajo") }
            AssistantCard("Custom MCP tools", "MCP", selected == "Custom MCP tools", connectedAssistant == "Custom MCP tools", "tool server · not an assistant by itself") { choose("Custom MCP tools") }
            Surface(Modifier.fillMaxWidth().padding(top = 16.dp), color = Bubble, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Edge)) {
                Text("Each connection has its own auth and capabilities. MCP servers expose tools; they do not all provide a conversational assistant. Native message reactions depend on the provider.", Modifier.padding(15.dp), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        PillButton("manage connections", back, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
    }
}

@Composable private fun AssistantCard(name: String, mark: String, selected: Boolean, connected: Boolean, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(18.dp), color = Bubble, border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = .65f) else Edge)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            if (name == "Pally") AssistantMark("Pally", AgentMode.IDLE, false, {}, size = 40) else Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, Edge, CircleShape), contentAlignment = Alignment.Center) { Text(mark, color = White, fontSize = 13.sp) }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(name, color = White, fontSize = 16.sp); if (connected) Text("  · connected", color = Accent, fontSize = 10.sp) }
                Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked, null, tint = if (selected) Accent else Muted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable private fun ConnectionScreen(
    assistant: String, endpoint: String, onEndpoint: (String) -> Unit, key: String, onKey: (String) -> Unit,
    authHeader: String, onHeader: (String) -> Unit, status: String, error: String,
    tools: List<McpTool>, busy: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit,
    openDocs: (String) -> Unit, back: () -> Unit
) {
    val isPally = assistant == "Pally"
    val isFo = assistant == "Fo by Wajo"
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTop(assistant, "${if (isPally) "Pally" else if (isFo) "Fo by Wajo" else "MCP server"} connection", back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            if (isPally) {
                Text("Pally MCP", color = White, fontSize = 20.sp)
                Text("Pally's public connector uses OAuth 2.1 with PKCE. This build does not complete the browser sign-in flow, so Pally is not connected.", Modifier.padding(top = 10.dp), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                PillButton("open Pally connection guide", { openDocs("Pally") }, Modifier.padding(top = 14.dp))
            } else {
                Text(if (isFo) "connect Fo's assistant profile" else "connect a compatible MCP endpoint", color = White, fontSize = 19.sp)
                if (isFo) {
                    Text(FoEndpoint, Modifier.padding(top = 12.dp, bottom = 6.dp), color = Accent, fontSize = 12.sp)
                    Text("Wajo documents ask_fo and get_fo_reply for this profile. This connects only to Fo, not arbitrary MCP assistants.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                } else {
                    OutlinedTextField(endpoint, onEndpoint, Modifier.fillMaxWidth().padding(top = 14.dp), label = { Text("HTTPS MCP endpoint") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("authentication", color = Muted, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        FilterChip(authHeader == "X-API-Key", { onHeader("X-API-Key") }, label = { Text("X-API-Key") })
                        Spacer(Modifier.width(5.dp))
                        FilterChip(authHeader == "Authorization", { onHeader("Authorization") }, label = { Text("Bearer") })
                    }
                }
                OutlinedTextField(key, onKey, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text(if (isFo) "Wajo API key (wak_…)" else "server credential") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("The credential is encrypted in Android Keystore storage on this phone. Never paste it into chat or public code. Connect only to a server you trust.", Modifier.padding(top = 8.dp), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (busy) "connecting…" else "connect and inspect tools", onConnect, Modifier.weight(1f), enabled = !busy)
                    OutlinedButton(onDisconnect, enabled = !busy) { Text("disconnect") }
                }
                Text(status, Modifier.padding(top = 12.dp), color = if (status.startsWith("connected")) Accent else Muted, fontSize = 12.sp)
                if (error.isNotBlank()) Text(error, Modifier.padding(top = 6.dp), color = Color(0xFFFFB4A8), fontSize = 12.sp, lineHeight = 17.sp)
                if (tools.isNotEmpty()) {
                    Text("server-advertised tools · ${tools.size}", Modifier.padding(top = 20.dp, bottom = 6.dp), color = White, fontSize = 15.sp)
                    tools.take(30).forEach { tool ->
                        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Bubble, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Edge)) {
                            Column(Modifier.padding(12.dp)) { Text(tool.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium); if (tool.description.isNotBlank()) Text(tool.description, Modifier.padding(top = 4.dp), color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3) }
                        }
                    }
                    Text("Generic MCP tools are listed for inspection only. This build does not call arbitrary tools or claim universal task support.", Modifier.padding(top = 8.dp), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
        if (!isPally) PillButton("back to assistants", back, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}

@Composable private fun SettingsScreen(motion: Boolean, onMotion: (Boolean) -> Unit, savedKey: Boolean, saveKey: (String) -> Unit, clearKey: () -> Unit, back: () -> Unit, preview: (AgentMode) -> Unit) {
    var key by remember { mutableStateOf("") }
    var label by remember(savedKey) { mutableStateOf(if (savedKey) "Gemini key saved encrypted on this device" else "No Gemini key saved") }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenTop("Pally", "preferences", back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Text("appearance", color = Muted, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("assistant motion", color = White, fontSize = 15.sp); Text("animate the Pally mark while working", color = Muted, fontSize = 11.sp) }; Switch(motion, onMotion) }
            MenuRow(Icons.Default.AutoAwesome, "preview thinking", "see the animated assistant mark", { preview(AgentMode.THINKING) })
            MenuRow(Icons.Default.Search, "preview searching", "see the search state", { preview(AgentMode.SEARCHING) })
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Edge)
            Text("Gemini Live voice", color = White, fontSize = 19.sp)
            Text("Optional API-key field for local setup. This build does not connect Live audio. For production, Google recommends short-lived ephemeral tokens issued by an authenticated backend.", Modifier.padding(top = 8.dp, bottom = 10.dp), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text(label, Modifier.padding(top = 7.dp), color = Muted, fontSize = 11.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("save on this device", { if (key.isNotBlank()) { saveKey(key.trim()); key = ""; label = "Gemini key saved encrypted on this device" } }, Modifier.weight(1f), enabled = key.isNotBlank())
                OutlinedButton(onClick = { clearKey(); key = ""; label = "Saved key erased" }) { Text("erase") }
            }
        }
        PillButton("done", back, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}

@Composable private fun VoiceScreen(motion: Boolean, keySaved: Boolean, settings: () -> Unit, back: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voice orb")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4800, easing = LinearEasing)), label = "orb phase")
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = settings) { Text("Gemini Live setup", color = Accent) }
            CircleButton(Icons.Default.Close, "Close voice", back)
        }
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(250.dp).semantics { contentDescription = "Animated voice orb preview" }) {
            val p = if (motion) phase else 0f
            val cx = size.width / 2; val cy = size.height / 2; val base = size.minDimension * .36f
            val path = Path()
            for (i in 0..160) {
                val a = i * 2.0 * Math.PI / 160.0
                val r = base * (1f + .07f * sin(a * 3.0 + p * 2 * Math.PI).toFloat() + .035f * cos(a * 5.0 - p * 2 * Math.PI).toFloat())
                val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawCircle(Color(0xFF344047).copy(alpha = .5f), radius = base * 1.25f, center = Offset(cx, cy))
            drawPath(path, Brush.radialGradient(listOf(Color(0xFFECF5EF), Color(0xFF98B5B6), Color(0xFF536D79), Color(0xFF26323A)), center = Offset(cx * .72f, cy * .70f), radius = base * 2f))
            drawPath(path, Color.White.copy(alpha = .38f), style = Stroke(1.2.dp.toPx()))
        }
        Spacer(Modifier.height(34.dp))
        Text("voice", color = White, fontSize = 24.sp)
        Text(if (keySaved) "Gemini key is stored locally, but live audio is not connected in this build." else "connect Gemini Live in preferences to continue", Modifier.padding(top = 10.dp), color = Muted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        Text("microphone and call session are preview-only", Modifier.padding(top = 6.dp), color = Muted.copy(alpha = .75f), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        PillButton("close", back, Modifier.padding(bottom = 20.dp))
    }
}

@Composable private fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(26.dp), color = Color(0xFF2C2A28), border = BorderStroke(1.dp, Edge)) {
        Box(contentAlignment = Alignment.Center) { Text(label, color = if (enabled) White else Muted, fontSize = 13.sp) }
    }
}

@Composable private fun AttachmentSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Bubble,
        title = { Text("add to message", color = White) },
        text = { Column { listOf("camera" to Icons.Default.CameraAlt, "photos" to Icons.Default.Image, "files" to Icons.Default.InsertDriveFile, "location" to Icons.Default.LocationOn).forEach { (name, icon) ->
            Row(Modifier.fillMaxWidth().clickable { onPick(name) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Muted, modifier = Modifier.size(21.dp)); Text(name, Modifier.padding(start = 12.dp), color = White, fontSize = 14.sp) }
            HorizontalDivider(color = Edge.copy(alpha = .45f))
        } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("close", color = Accent) } })
}

@Composable private fun EmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Bubble, title = { Text("choose emoji", color = White) },
        text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { listOf("😊", "👍", "❤️", "✨", "🙏").forEach { Text(it, Modifier.clickable { onPick(it) }.padding(7.dp), fontSize = 24.sp) } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("close", color = Accent) } })
}
