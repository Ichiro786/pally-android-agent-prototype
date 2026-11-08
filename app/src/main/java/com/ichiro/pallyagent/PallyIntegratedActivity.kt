package com.ichiro.pallyagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF070707)
private val Bubble = Color(0xFF20201F)
private val Edge = Color(0xFF454341)
private val White = Color(0xFFF4F2EF)
private val Muted = Color(0xFFAAA7A4)
private val Accent = Color(0xFFB8D5D0)
private const val FO_URL = "https://mcp.api.wajo.ai/fo"
private const val FO_KEY = "wajo_mcp_api_key"
private const val GEMINI_KEY = "gemini_live_key"
private const val ASSISTANT_PREF = "selected_assistant"

private enum class Page { CHAT, ASSISTANTS, CONNECT, VOICE, SETTINGS }
private data class Line(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val assistant: Boolean,
    val local: Boolean = false,
    val sample: Boolean = false,
    val quote: String? = null,
    val time: String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
)

class PallyIntegratedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        intent?.data?.let { PallyOAuthCallback.events.trySend(it) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Bubble, onSurface = White)) {
                IntegratedApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { PallyOAuthCallback.events.trySend(it) }
    }
}

@Composable
private fun IntegratedApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.CHAT) }
    var assistant by remember {
        mutableStateOf(context.getSharedPreferences("agent_state", Context.MODE_PRIVATE).getString(ASSISTANT_PREF, "Pally") ?: "Pally")
    }
    var lines by remember { mutableStateOf<List<Line>>(emptyList()) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("not connected") }
    var endpoint by remember { mutableStateOf(FO_URL) }
    var secret by remember { mutableStateOf("") }
    var authHeader by remember { mutableStateOf("X-API-Key") }
    var client by remember { mutableStateOf<McpClient?>(null) }
    var clientName by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var motion by remember { mutableStateOf(true) }
    var geminiSaved by remember { mutableStateOf(SecretVault.read(context, GEMINI_KEY) != null) }
    val connected = client != null && clientName == assistant

    suspend fun attachPally(token: String) {
        val live = McpClient(PallyOAuth.MCP_ENDPOINT, token, "Authorization")
        val advertised = live.connectAndListTools()
        check(advertised.any { it.name == "message_pally" }) { "Pally did not advertise message_pally." }
        client = live
        clientName = "Pally"
        tools = advertised
        status = "connected · ${advertised.size} tools"
        error = ""
    }

    LaunchedEffect(assistant) {
        val selected = assistant
        loadedFor = null
        val restored = withContext(Dispatchers.IO) { loadHistory(context, selected) }.ifEmpty { demoLines() }
        if (assistant == selected) {
            lines = restored
            loadedFor = selected
        }
    }
    LaunchedEffect(assistant, lines, loadedFor) {
        val selected = assistant
        if (loadedFor != selected) return@LaunchedEffect
        val snapshot = lines
        withContext(Dispatchers.IO) { saveHistory(context, selected, snapshot) }
    }
    LaunchedEffect(Unit) {
        for (uri in PallyOAuthCallback.events) {
            if (!PallyOAuth.isRedirect(uri)) continue
            busy = true
            status = "finishing secure sign-in…"
            try {
                val token = withContext(Dispatchers.IO) { PallyOAuth.finish(context, uri) }
                attachPally(token)
                page = Page.CHAT
                note = "Pally is connected on this device."
            } catch (e: Exception) {
                status = "sign-in failed"
                error = e.message ?: "Could not finish Pally sign-in."
            } finally { busy = false }
        }
    }

    fun selectAssistant(name: String) {
        assistant = name
        context.getSharedPreferences("agent_state", Context.MODE_PRIVATE).edit().putString(ASSISTANT_PREF, name).apply()
        if (name == "Fo by Wajo") { endpoint = FO_URL; authHeader = "X-API-Key" }
        error = ""
        page = Page.CONNECT
    }

    fun connect() {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                if (assistant == "Pally") {
                    val token = withContext(Dispatchers.IO) { PallyOAuth.accessToken(context) }
                    if (token != null) attachPally(token) else {
                        val url = withContext(Dispatchers.IO) { PallyOAuth.begin(context) }
                        status = "waiting for Pally approval in browser…"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                } else {
                    val target = if (assistant == "Fo by Wajo") FO_URL else endpoint.trim()
                    val key = secret.trim().ifBlank { if (assistant == "Fo by Wajo") SecretVault.read(context, FO_KEY).orEmpty() else "" }
                    require(target.startsWith("https://")) { "Use an HTTPS MCP endpoint." }
                    if (assistant == "Fo by Wajo") require(key.startsWith("wak_")) { "Enter your Wajo API key (wak_…)." }
                    status = "connecting…"
                    val live = withContext(Dispatchers.IO) {
                        McpClient(target, key, if (assistant == "Fo by Wajo") "X-API-Key" else authHeader).also { it.connectAndListTools() }
                    }
                    val advertised = withContext(Dispatchers.IO) { live.connectAndListTools() }
                    if (assistant == "Fo by Wajo") check(advertised.any { it.name == "ask_fo" }) { "This endpoint did not advertise ask_fo, so it is not the documented Fo profile." }
                    client = live
                    clientName = assistant
                    tools = advertised
                    if (assistant == "Fo by Wajo") withContext(Dispatchers.IO) { SecretVault.save(context, FO_KEY, key) }
                    secret = ""
                    status = "connected · ${advertised.size} tools"
                }
            } catch (e: Exception) {
                status = "connection failed"
                error = e.message ?: "Could not connect."
            } finally { busy = false }
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isBlank() || busy || loadedFor != assistant) return
        if (lines.any { it.sample }) lines = emptyList()
        val quoted = quote
        quote = null
        draft = ""
        if (!connected || assistant !in listOf("Pally", "Fo by Wajo")) {
            lines = lines + Line(text = text, assistant = false, local = true, quote = quoted)
            note = "saved on this device only · connect $assistant to send messages"
            return
        }
        lines = lines + Line(text = text, assistant = false, quote = quoted)
        busy = true
        searching = false
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    if (assistant == "Pally") {
                        val token = PallyOAuth.accessToken(context) ?: error("Pally sign-in expired. Reconnect to continue.")
                        val live = McpClient(PallyOAuth.MCP_ENDPOINT, token, "Authorization")
                        val advertised = live.connectAndListTools()
                        check(advertised.any { it.name == "message_pally" }) { "Pally did not advertise message_pally." }
                        client = live
                        tools = advertised
                        PallyAdapter.send(live, advertised, text)
                    } else {
                        FoAdapter.send(client ?: error("Reconnect to Fo first."), tools, text)
                    }
                }
                lines = lines + Line(text = answer, assistant = true)
            } catch (e: Exception) {
                lines = lines + Line(text = "I couldn't get a reply from $assistant: ${e.message ?: "connection error"}", assistant = true)
            } finally { busy = false; searching = false }
        }
    }

    BackHandler(page != Page.CHAT || drawer || sheet.isNotBlank()) {
        when {
            sheet.isNotBlank() -> sheet = ""
            drawer -> drawer = false
            else -> page = Page.CHAT
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Scanlines(Modifier.matchParentSize())
        when (page) {
            Page.CHAT -> ChatPage(
                assistant, connected, lines, draft, { draft = it }, busy, searching, motion, note,
                onMenu = { drawer = true }, onChoose = { page = Page.ASSISTANTS }, onVoice = { page = Page.VOICE },
                onAdd = { sheet = "attachments" }, onEmoji = { sheet = "emoji" }, onSend = { send() },
                onReply = { quote = if (it.assistant) "$assistant: ${it.text}" else "you: ${it.text}" }, onDismiss = { note = "" }
            )
            Page.ASSISTANTS -> AssistantsPage(assistant, clientName, ::selectAssistant) { page = Page.CHAT }
            Page.CONNECT -> ConnectPage(
                assistant, endpoint, { endpoint = it }, secret, { secret = it }, authHeader, { authHeader = it },
                status, error, tools, busy, ::connect,
                onDisconnect = {
                    client = null; clientName = ""; tools = emptyList()
                    if (assistant == "Pally") PallyOAuth.disconnect(context)
                    if (assistant == "Fo by Wajo") SecretVault.clear(context, FO_KEY)
                    status = "disconnected"; error = "Saved credentials for $assistant were erased from this device."
                }, onBack = { page = Page.ASSISTANTS }
            )
            Page.VOICE -> VoicePage(motion, geminiSaved, { page = Page.SETTINGS }) { page = Page.CHAT }
            Page.SETTINGS -> SettingsPage(
                geminiSaved, motion, { motion = it },
                save = { value -> SecretVault.save(context, GEMINI_KEY, value); geminiSaved = true },
                erase = { SecretVault.clear(context, GEMINI_KEY); geminiSaved = false }, back = { page = Page.VOICE }
            )
        }
        if (page == Page.CHAT && drawer) WorkDrawer(
            close = { drawer = false }, assistants = { drawer = false; page = Page.ASSISTANTS }, settings = { drawer = false; page = Page.SETTINGS }
        )
        if (page == Page.CHAT && sheet.isNotBlank()) {
            if (sheet == "emoji") AlertDialog(
                onDismissRequest = { sheet = "" }, containerColor = Bubble,
                title = { Text("choose emoji", color = White) },
                text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    listOf("😊", "👍", "❤️", "✨", "🙏").forEach { Text(it, Modifier.clickable { draft += it; sheet = "" }.padding(7.dp), fontSize = 24.sp) }
                } }, confirmButton = { TextButton(onClick = { sheet = "" }) { Text("close", color = Accent) } }
            ) else AlertDialog(
                onDismissRequest = { sheet = "" }, containerColor = Bubble, title = { Text("attachments", color = White) },
                text = { Text("The selected MCP provider has no verified attachment-send contract in this app, so files are not uploaded.", color = Muted) },
                confirmButton = { TextButton(onClick = { sheet = "" }) { Text("close", color = Accent) } }
            )
        }
    }
}

@Composable private fun Scanlines(modifier: Modifier) {
    Canvas(modifier) {
        val brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .013f), Color.White.copy(alpha = .024f), Color.White.copy(alpha = .013f), Color.Transparent))
        var y = 0f
        while (y < size.height) { drawLine(brush, Offset(0f, y), Offset(size.width, y), strokeWidth = .55.dp.toPx()); y += 5.dp.toPx() }
    }
}

@Composable private fun Control(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: Int = 54) {
    Surface(onClick = onClick, modifier = Modifier.size(size.dp).semantics { contentDescription = label }, color = Color(0xFF161616).copy(alpha = .94f), shape = CircleShape, border = BorderStroke(1.dp, Edge)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = White, modifier = Modifier.size(24.dp)) }
    }
}

@Composable private fun Mark(name: String, active: Boolean, motion: Boolean, onClick: () -> Unit, size: Int = 62) {
    val transition = rememberInfiniteTransition(label = "pally-mark")
    val pulse by transition.animateFloat(.97f, 1.035f, infiniteRepeatable(tween(1050), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(size.dp).clip(CircleShape).clickable(onClick = onClick).background(Color(0xFF0A0A0A)).padding(4.dp), contentAlignment = Alignment.Center) {
        if (active && motion) Canvas(Modifier.fillMaxSize()) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Accent, Color.Transparent)), style = Stroke(1.6.dp.toPx()), radius = this.size.minDimension / 2f, center = center)
        }
        if (name == "Pally") {
            androidx.compose.foundation.Image(painterResource(R.drawable.pally_logo), "Pally", Modifier.size((size - 12).dp).scale(if (active && motion) pulse else 1f).clip(CircleShape))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (name == "Fo by Wajo") "Fo" else "MCP", color = White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (name == "Fo by Wajo") Text("by Wajo", color = Muted, fontSize = 8.sp)
            }
        }
    }
}

@Composable private fun ChatPage(
    name: String, connected: Boolean, lines: List<Line>, draft: String, onDraft: (String) -> Unit,
    busy: Boolean, searching: Boolean, motion: Boolean, note: String,
    onMenu: () -> Unit, onChoose: () -> Unit, onVoice: () -> Unit, onAdd: () -> Unit,
    onEmoji: () -> Unit, onSend: () -> Unit, onReply: (Line) -> Unit, onDismiss: () -> Unit
) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Control(Icons.Default.Menu, "Open your work", onMenu)
                Mark(name, busy, motion, onChoose, 64)
                Control(Icons.Default.Call, "Open voice", onVoice)
            }
            Text("$name · ${if (connected) "connected" else "not connected"}", Modifier.padding(bottom = 10.dp), color = Muted, fontSize = 13.sp)
        }
        if (lines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("your conversation starts here", Modifier.padding(horizontal = 35.dp).offset(y = (-35).dp), color = White, fontSize = 27.sp, lineHeight = 34.sp, textAlign = TextAlign.Center)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = Edge.copy(alpha = .5f))
                Text("today", Modifier.padding(horizontal = 14.dp), color = Muted, fontSize = 12.sp)
                androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = Edge.copy(alpha = .5f))
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lines.any { it.sample }) item { Text("sample preview · not connected", Modifier.fillMaxWidth(), color = Muted.copy(alpha = .75f), fontSize = 10.sp, textAlign = TextAlign.Center) }
                items(lines, key = { it.id }) { line -> BubbleLine(line, onReply) }
                if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) { Mark(name, true, motion, {}, 30); Text(if (searching) "  searching" else "  $name is thinking", color = Muted, fontSize = 12.sp) } }
            }
        }
        if (note.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), color = Color(0xFF171717), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Edge)) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note, Modifier.weight(1f), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                TextButton(onClick = onDismiss) { Text("ok", color = Accent) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Control(Icons.Default.Add, "Add attachment", onAdd, 52)
            Surface(Modifier.weight(1f).heightIn(min = 54.dp), color = Color(0xFF1C1B1A), shape = RoundedCornerShape(30.dp), border = BorderStroke(1.dp, Edge)) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(draft, onDraft, Modifier.weight(1f).padding(vertical = 14.dp), textStyle = TextStyle(color = White, fontSize = 16.sp, lineHeight = 21.sp), maxLines = 4, decorationBox = { inner -> if (draft.isEmpty()) Text("message $name", color = Muted, fontSize = 15.sp); inner() })
                    IconButton(onClick = onEmoji) { Icon(Icons.Default.EmojiEmotions, "Choose emoji", tint = White) }
                }
            }
            Control(if (draft.isBlank()) Icons.Default.Call else Icons.AutoMirrored.Filled.Send, if (draft.isBlank()) "Open voice" else "Send message", if (draft.isBlank()) onVoice else onSend, 52)
        }
    }
}

@Composable private fun BubbleLine(line: Line, onReply: (Line) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.assistant) Arrangement.Start else Arrangement.End) {
        Surface(Modifier.widthIn(max = 340.dp).fillMaxWidth(.88f).pointerInput(line.id) { detectTapGestures(onLongPress = { onReply(line) }) }, color = if (line.assistant) Bubble else Color(0xFF111111), shape = RoundedCornerShape(20.dp), border = if (line.assistant) null else BorderStroke(1.dp, Edge)) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                line.quote?.let { Text(it, Modifier.fillMaxWidth().background(Color(0xFF383735), RoundedCornerShape(12.dp)).padding(9.dp), color = Muted, fontSize = 11.sp, maxLines = 2) }
                Text(line.text, Modifier.padding(top = if (line.quote == null) 0.dp else 7.dp), color = White, fontSize = 16.sp, lineHeight = 23.sp)
                Row(Modifier.align(Alignment.End).padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (line.local) Text("local · not sent  ", color = Muted, fontSize = 9.sp)
                    Text(line.time, color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable private fun WorkDrawer(close: () -> Unit, assistants: () -> Unit, settings: () -> Unit) {
    Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .64f))) {
        Column(Modifier.fillMaxHeight().fillMaxWidth(.8f).background(Color(0xFF0B0B0B)).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("our work", color = White, fontSize = 24.sp); Control(Icons.Default.Close, "Close", close, 44) }
            Spacer(Modifier.height(24.dp)); androidx.compose.material3.HorizontalDivider(color = Edge)
            Text("in progress", Modifier.padding(top = 22.dp, bottom = 10.dp), color = Muted, fontSize = 12.sp)
            Text("no active work", color = Muted, fontSize = 14.sp)
            Text("connect", Modifier.padding(top = 24.dp, bottom = 8.dp), color = Muted, fontSize = 12.sp)
            DrawerRow("assistants & tools", "manage assistant connections", assistants)
            Spacer(Modifier.weight(1f)); DrawerRow("preferences", "motion and Gemini Live setup", settings)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = close))
    }
}

@Composable private fun DrawerRow(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), color = Color.Transparent) {
        Column(Modifier.padding(vertical = 9.dp)) { Text(title, color = White, fontSize = 15.sp); Text(detail, Modifier.padding(top = 3.dp), color = Muted, fontSize = 11.sp) }
    }
}

@Composable private fun AssistantsPage(selected: String, connected: String, choose: (String) -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        PageHeader("assistants", selected, back)
        Text("choose the assistant for this conversation", color = Muted, fontSize = 13.sp)
        listOf("Pally" to "Official assistant · OAuth sign-in", "Fo by Wajo" to "Wajo's single-assistant MCP profile", "Custom MCP tools" to "Inspect capabilities · not automatically a chat agent").forEach { (name, detail) ->
            Surface(onClick = { choose(name) }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), color = Bubble, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, if (selected == name) Accent else Edge)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Mark(name, false, false, {}, 42)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(name + if (connected == name) " · connected" else "", color = White, fontSize = 15.sp); Text(detail, Modifier.padding(top = 4.dp), color = Muted, fontSize = 11.sp, lineHeight = 15.sp) }
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 14.dp), color = Bubble, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Edge)) {
            Text("MCP capabilities vary by server. Native reactions and attachments are available only if that provider exposes them; this app does not fake either feature.", Modifier.padding(13.dp), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        TextButton(onClick = back, modifier = Modifier.align(Alignment.End)) { Text("back to chat", color = Accent) }
    }
}

@Composable private fun PageHeader(title: String, name: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Control(Icons.Default.Close, "Back", back, 46)
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Mark(name, false, false, {}, 52); Text(title, Modifier.padding(top = 6.dp), color = White, fontSize = 20.sp) }
        Spacer(Modifier.size(46.dp))
    }
}

@Composable private fun ConnectPage(
    name: String, endpoint: String, setEndpoint: (String) -> Unit, secret: String, setSecret: (String) -> Unit,
    authHeader: String, setHeader: (String) -> Unit, status: String, error: String, tools: List<McpTool>, busy: Boolean,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onBack: () -> Unit
) {
    val isPally = name == "Pally"
    val isFo = name == "Fo by Wajo"
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp)) {
        PageHeader(if (isPally) "Pally connection" else if (isFo) "Fo by Wajo" else "MCP tools", name, onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (isPally) {
                Text("Pally MCP", color = White, fontSize = 20.sp)
                Text("Public-client OAuth 2.1 with dynamic registration, S256 PKCE, callback-state validation and encrypted device tokens. Sign in in your browser; the app returns here after authorization.", Modifier.padding(top = 9.dp), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
            } else {
                Text(if (isFo) "connect Fo's assistant profile" else "connect a trusted MCP endpoint", color = White, fontSize = 18.sp)
                if (isFo) {
                    Text(FO_URL, Modifier.padding(top = 9.dp), color = Accent, fontSize = 12.sp)
                    Text("Wajo documents ask_fo and get_fo_reply for this single-assistant profile. A live connection still needs testing with your Wajo account.", Modifier.padding(top = 5.dp), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
                } else {
                    OutlinedTextField(endpoint, setEndpoint, Modifier.fillMaxWidth().padding(top = 9.dp), label = { Text("HTTPS MCP endpoint") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Row {
                        FilterChip(selected = authHeader == "X-API-Key", onClick = { setHeader("X-API-Key") }, label = { Text("X-API-Key") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = authHeader == "Authorization", onClick = { setHeader("Authorization") }, label = { Text("Bearer") })
                    }
                }
                OutlinedTextField(secret, setSecret, Modifier.fillMaxWidth().padding(top = 9.dp), label = { Text(if (isFo) "Wajo API key (wak_…)" else "server credential (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("Credential is encrypted on this phone after a successful connection. Never paste it into chat or commit it to the public repo.", Modifier.padding(top = 7.dp), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text(if (busy) "connecting…" else if (isPally) "sign in with Pally" else "connect and inspect tools") }
            OutlinedButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.padding(top = 5.dp)) { Text("disconnect and erase saved credentials") }
            Text(status, Modifier.padding(top = 10.dp), color = if (status.startsWith("connected")) Accent else Muted, fontSize = 12.sp)
            if (error.isNotBlank()) Text(error, Modifier.padding(top = 4.dp), color = Color(0xFFFFB4A8), fontSize = 12.sp)
            if (tools.isNotEmpty()) {
                Text("server-advertised tools · ${tools.size}", Modifier.padding(top = 16.dp, bottom = 5.dp), color = White, fontSize = 14.sp)
                tools.take(30).forEach { tool ->
                    Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp), color = Bubble, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Edge)) {
                        Column(Modifier.padding(10.dp)) { Text(tool.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium); if (tool.description.isNotBlank()) Text(tool.description, Modifier.padding(top = 3.dp), color = Muted, fontSize = 11.sp, maxLines = 3) }
                    }
                }
                Text("The app calls only the documented Pally/Fo chat tools. Arbitrary MCP tools are not run.", Modifier.padding(top = 7.dp), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable private fun VoicePage(motion: Boolean, saved: Boolean, settings: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var status by remember { mutableStateOf("ready") }
    var transcript by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<GeminiLiveSession?>(null) }
    val transition = rememberInfiniteTransition(label = "voice-orb")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200), RepeatMode.Reverse), label = "orb")
    fun startWithKey(key: String) {
        if (key.isBlank()) { status = "add a Gemini API key in preferences"; settings(); return }
        transcript = ""
        status = "connecting…"
        val live = GeminiLiveSession(key, object : GeminiLiveSession.Listener {
            override fun onState(value: String) { main.post { status = value } }
            override fun onTranscript(text: String, fromModel: Boolean) {
                main.post { transcript = (transcript + if (transcript.isBlank()) "" else "\n" + if (fromModel) "Gemini: " else "you: ") + text }
            }
            override fun onError(message: String) { main.post { status = "call ended"; transcript = message } }
        })
        session = live
        runCatching { live.start() }.onFailure { status = it.message ?: "Gemini Live could not start." }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startWithKey(SecretVault.read(context, GEMINI_KEY).orEmpty()) else status = "microphone permission denied"
    }
    DisposableEffect(session) { onDispose { session?.stop() } }
    val active = status == "listening" || status == "speaking" || status == "connecting…"
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = settings) { Text("Gemini Live setup", color = Accent) }
            Control(Icons.Default.Close, "Close voice", back)
        }
        Spacer(Modifier.weight(1f))
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
        Spacer(Modifier.height(26.dp))
        Text("Gemini Live", color = White, fontSize = 24.sp)
        Text(if (saved) status else "add a Gemini API key in preferences", Modifier.padding(top = 9.dp), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (transcript.isNotBlank()) Text(transcript, Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(max = 72.dp), color = White, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center, maxLines = 4)
        Text("audio streams directly to Google; no MCP tools run in this call", Modifier.padding(top = 7.dp), color = Muted.copy(alpha = .76f), fontSize = 10.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Button(onClick = {
            if (session != null) { session?.stop(); session = null; status = "call ended" }
            else if (!saved) { status = "add a Gemini API key in preferences"; settings() }
            else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO)
            else startWithKey(SecretVault.read(context, GEMINI_KEY).orEmpty())
        }, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) { Text(if (session != null) "end voice call" else "start voice call") }
    }
}

@Composable private fun SettingsPage(saved: Boolean, motion: Boolean, setMotion: (Boolean) -> Unit, save: (String) -> Unit, erase: () -> Unit, back: () -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp)) {
        PageHeader("preferences", "Pally", back)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("assistant motion", color = White); Text("animate thinking and search states", color = Muted, fontSize = 11.sp) }
                Switch(motion, setMotion)
            }
            Text("Gemini Live setup", Modifier.padding(top = 10.dp), color = White, fontSize = 18.sp)
            Text("This prototype uses the saved key directly for Gemini Live. Device-side keys can be extracted, so use a test key only. For production, use an authenticated backend that issues a short-lived ephemeral token. The microphone is requested only when starting a call.", Modifier.padding(vertical = 9.dp), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text(if (saved) "key saved encrypted on this device" else "no key saved", Modifier.padding(top = 7.dp), color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (key.isNotBlank()) { save(key.trim()); key = "" } }, enabled = key.isNotBlank()) { Text("save on this device") }
                OutlinedButton(onClick = { erase(); key = "" }) { Text("erase") }
            }
        }
        TextButton(onClick = back, modifier = Modifier.align(Alignment.End)) { Text("done", color = Accent) }
    }
}

private fun demoLines() = listOf(
    Line(text = "i can help you think it through.\nwhat matters most right now?", assistant = true, sample = true, time = "1:40 am"),
    Line(text = "compare a few options for this weekend", assistant = false, sample = true, quote = "you: can you help me choose?", time = "1:41 am"),
    Line(text = "sure. tell me what you want to optimize for, and i'll keep the trade-offs clear.", assistant = true, sample = true, time = "1:41 am"),
    Line(text = "keep it simple, and call out the biggest difference", assistant = false, sample = true, time = "1:42 am"),
    Line(text = "got it. i'll keep the comparison short and easy to scan.", assistant = true, sample = true, time = "1:42 am")
)

private fun loadHistory(context: Context, name: String): List<Line> {
    val saved = SecretVault.read(context, "chat_history:$name") ?: return emptyList()
    return try {
        val rows = JSONArray(saved)
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val text = row.optString("text")
                if (text.isNotBlank()) add(Line(
                    id = row.optString("id").ifBlank { UUID.randomUUID().toString() },
                    text = text,
                    assistant = row.optBoolean("assistant"),
                    local = row.optBoolean("local"),
                    quote = row.optString("quote").takeIf { it.isNotBlank() },
                    time = row.optString("time").ifBlank { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) }
                ))
            }
        }
    } catch (_: Exception) {
        SecretVault.clear(context, "chat_history:$name")
        emptyList()
    }
}

private fun saveHistory(context: Context, name: String, lines: List<Line>) {
    val rows = JSONArray()
    lines.filterNot { it.sample }.takeLast(200).forEach { line ->
        val row = JSONObject().put("id", line.id).put("text", line.text).put("assistant", line.assistant).put("local", line.local).put("time", line.time)
        line.quote?.let { row.put("quote", it) }
        rows.put(row)
    }
    SecretVault.save(context, "chat_history:$name", rows.toString())
}
