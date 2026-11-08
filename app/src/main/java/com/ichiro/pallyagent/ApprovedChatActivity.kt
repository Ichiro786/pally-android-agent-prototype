package com.ichiro.pallyagent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

private val UiInk = Color(0xFF070707)
private val UiBubble = Color(0xFF20201F)
private val UiEdge = Color(0xFF454341)
private val UiWhite = Color(0xFFF4F2EF)
private val UiMuted = Color(0xFFAAA7A4)
private val UiAccent = Color(0xFFB8D5D0)
private const val WajoFoEndpoint = "https://mcp.api.wajo.ai/fo"
private const val WajoFoSecret = "wajo_mcp_api_key"
private const val GeminiLiveSecret = "gemini_live_key"

private enum class AgentScreen { CHAT, CONNECTIONS, CONNECT, VOICE, SETTINGS }
private enum class AgentActivity { IDLE, THINKING, SEARCHING }
private data class AgentMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fromAssistant: Boolean,
    val quoted: String? = null,
    val localOnly: Boolean = false,
    val sample: Boolean = false,
    val time: String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
)

class ApprovedChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = UiAccent, background = UiInk, surface = UiBubble, onSurface = UiWhite)) {
                ApprovedAgentApp()
            }
        }
    }
}

@Composable private fun ApprovedAgentApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(AgentScreen.CHAT) }
    var assistant by remember { mutableStateOf(context.getSharedPreferences("agent_state", Context.MODE_PRIVATE).getString("selected_assistant", "Pally") ?: "Pally") }
    var drawer by remember { mutableStateOf(false) }
    var showAttachments by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var activity by remember { mutableStateOf(AgentActivity.IDLE) }
    var client by remember { mutableStateOf<McpClient?>(null) }
    var clientName by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var endpoint by remember { mutableStateOf(WajoFoEndpoint) }
    var credential by remember { mutableStateOf("") }
    var authHeader by remember { mutableStateOf("X-API-Key") }
    var connectionStatus by remember { mutableStateOf("not connected") }
    var error by remember { mutableStateOf("") }
    var geminiSaved by remember { mutableStateOf(SecretVault.read(context, GeminiLiveSecret) != null) }
    var motion by remember { mutableStateOf(true) }
    val messages = remember { mutableStateListOf<AgentMessage>() }
    var historyReady by remember { mutableStateOf(false) }
    val connected = client != null && clientName == assistant

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { loadChatHistory(context, assistant) }
        if (saved.isNotEmpty()) messages.addAll(saved) else messages.addAll(sampleConversation())
        historyReady = true
    }
    LaunchedEffect(assistant, messages.size, historyReady) {
        if (historyReady) {
            val snapshot = messages.filterNot { it.sample }.takeLast(200)
            withContext(Dispatchers.IO) { saveChatHistory(context, assistant, snapshot) }
        }
    }

    BackHandler(screen != AgentScreen.CHAT || drawer || showAttachments || showEmoji) {
        when {
            showAttachments -> showAttachments = false
            showEmoji -> showEmoji = false
            drawer -> drawer = false
            else -> screen = AgentScreen.CHAT
        }
    }

    fun connectMcp() {
        if (assistant == "Pally") {
            connectionStatus = "sign-in required"
            error = "Pally requires OAuth 2.1 with PKCE. Browser sign-in is not implemented in this build."
            return
        }
        val target = if (assistant == "Fo by Wajo") WajoFoEndpoint else endpoint.trim()
        val key = credential.trim().ifBlank { if (assistant == "Fo by Wajo") SecretVault.read(context, WajoFoSecret).orEmpty() else "" }
        if (!target.startsWith("https://")) {
            error = "Use an HTTPS MCP endpoint."
            return
        }
        if (assistant == "Fo by Wajo" && !key.startsWith("wak_")) {
            error = "Enter your Wajo API key in the app. Do not send it in chat."
            return
        }
        error = ""
        connectionStatus = "connecting…"
        busy = true
        scope.launch {
            try {
                val opened = McpClient(target, key, if (assistant == "Fo by Wajo") "X-API-Key" else authHeader)
                val available = opened.connectAndListTools()
                if (assistant == "Fo by Wajo" && available.none { it.name == "ask_fo" }) {
                    throw IllegalStateException("This endpoint did not advertise ask_fo, so it is not the documented Fo profile.")
                }
                client = opened
                clientName = assistant
                tools = available
                connectionStatus = "connected · ${available.size} tools"
                if (assistant == "Fo by Wajo") SecretVault.save(context, WajoFoSecret, key)
                credential = ""
            } catch (e: Exception) {
                client = null
                clientName = ""
                tools = emptyList()
                connectionStatus = "connection failed"
                error = e.message ?: "Could not connect to this MCP server."
            } finally {
                busy = false
            }
        }
    }

    fun sendMessage() {
        val text = draft.trim()
        if (text.isEmpty() || busy) return
        // Clear the illustrative first-run thread so it never appears to be the user's own conversation.
        if (messages.any { it.sample }) messages.clear()
        val quote = replyTo
        replyTo = null
        draft = ""
        if (assistant == "Fo by Wajo" && connected) {
            messages += AgentMessage(text = text, fromAssistant = false, quoted = quote)
            busy = true
            activity = AgentActivity.THINKING
            scope.launch {
                try {
                    val reply = FoAdapter.send(client ?: error("Reconnect to Fo first."), tools, text)
                    messages += AgentMessage(text = reply, fromAssistant = true)
                } catch (e: Exception) {
                    messages += AgentMessage(text = "I couldn't get a reply from Fo: ${e.message ?: "connection error"}", fromAssistant = true)
                } finally {
                    busy = false
                    activity = AgentActivity.IDLE
                }
            }
        } else {
            messages += AgentMessage(text = text, fromAssistant = false, quoted = quote, localOnly = true)
            note = if (assistant == "Pally") "This message stays on this screen. Pally sign-in is not available yet." else "This MCP connection is for inspecting tools only; it is not set up as a chat assistant."
        }
    }

    Box(Modifier.fillMaxSize().background(UiInk)) {
        FadingScanlines(Modifier.matchParentSize())
        when (screen) {
            AgentScreen.CHAT -> AgentChatScreen(
                assistant = assistant,
                connected = connected,
                messages = messages,
                draft = draft,
                onDraft = { draft = it },
                busy = busy,
                activity = activity,
                motion = motion,
                note = note,
                onMenu = { drawer = true },
                onAssistant = { screen = AgentScreen.CONNECTIONS },
                onVoice = { screen = AgentScreen.VOICE },
                onAdd = { showAttachments = true },
                onEmoji = { showEmoji = true },
                onSend = { sendMessage() },
                onReply = { message -> replyTo = if (message.fromAssistant) "$assistant: ${message.text}" else "you: ${message.text}" },
                onDismissNote = { note = "" }
            )
            AgentScreen.CONNECTIONS -> ConnectionsScreen(
                selected = assistant,
                connectedName = clientName,
                onChoose = { chosen ->
                    historyReady = false
                    assistant = chosen
                    context.getSharedPreferences("agent_state", Context.MODE_PRIVATE).edit().putString("selected_assistant", chosen).apply()
                    messages.clear()
                    messages.addAll(loadChatHistory(context, chosen).ifEmpty { sampleConversation() })
                    historyReady = true
                    screen = AgentScreen.CONNECT
                    error = ""
                    if (chosen == "Fo by Wajo") { endpoint = WajoFoEndpoint; authHeader = "X-API-Key" }
                },
                onBack = { screen = AgentScreen.CHAT }
            )
            AgentScreen.CONNECT -> ConnectionScreen(
                assistant = assistant,
                endpoint = endpoint,
                onEndpoint = { endpoint = it },
                credential = credential,
                onCredential = { credential = it },
                authHeader = authHeader,
                onAuthHeader = { authHeader = it },
                status = connectionStatus,
                error = error,
                tools = tools,
                busy = busy,
                onConnect = { connectMcp() },
                onDisconnect = {
                    client = null; clientName = ""; tools = emptyList()
                    if (assistant == "Fo by Wajo") SecretVault.clear(context, WajoFoSecret)
                    connectionStatus = "disconnected"
                    error = "Connection closed. The saved Wajo key was erased from this device."
                },
                onBack = { screen = AgentScreen.CONNECTIONS },
                onOpenPally = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pally.com/mcp"))) }
                }
            )
            AgentScreen.VOICE -> VoicePreviewScreen(
                motion = motion,
                geminiSaved = geminiSaved,
                onSettings = { screen = AgentScreen.SETTINGS },
                onBack = { screen = AgentScreen.CHAT }
            )
            AgentScreen.SETTINGS -> GeminiSettingsScreen(
                saved = geminiSaved,
                motion = motion,
                onMotion = { motion = it },
                onSave = { value -> SecretVault.save(context, GeminiLiveSecret, value); geminiSaved = value.isNotBlank() },
                onErase = { SecretVault.clear(context, GeminiLiveSecret); geminiSaved = false },
                onBack = { screen = AgentScreen.VOICE }
            )
        }
        if (screen == AgentScreen.CHAT && drawer) WorkDrawer(
            onClose = { drawer = false },
            onConnections = { drawer = false; screen = AgentScreen.CONNECTIONS },
            onSettings = { drawer = false; screen = AgentScreen.SETTINGS },
            onMcp = { drawer = false; assistant = "Custom MCP tools"; screen = AgentScreen.CONNECT; error = "" }
        )
        if (screen == AgentScreen.CHAT && showAttachments) AttachmentNotice(
            onDismiss = { showAttachments = false },
            onPick = { kind -> showAttachments = false; note = "$kind selection is not sent in this preview yet." }
        )
        if (screen == AgentScreen.CHAT && showEmoji) EmojiSheet(
            onDismiss = { showEmoji = false },
            onPick = { emoji -> draft += emoji; showEmoji = false }
        )
    }
}

@Composable private fun FadingScanlines(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val fade = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .014f), Color.White.copy(alpha = .026f), Color.White.copy(alpha = .014f), Color.Transparent))
        val step = 5.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(fade, Offset(0f, y), Offset(size.width, y), strokeWidth = .55.dp.toPx())
            y += step
        }
    }
}

@Composable private fun RoundControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: Int = 56) {
    Surface(onClick = onClick, modifier = Modifier.size(size.dp).semantics { contentDescription = label }, shape = CircleShape, color = Color(0xFF161616).copy(alpha = .92f), border = BorderStroke(1.dp, UiEdge)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = UiWhite, modifier = Modifier.size(25.dp)) }
    }
}

@Composable private fun AgentMark(name: String, activity: AgentActivity, motion: Boolean, onClick: () -> Unit, size: Int = 72) {
    val active = motion && activity != AgentActivity.IDLE
    val transition = rememberInfiniteTransition(label = "assistant-mark")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = FastOutSlowInEasing)), label = "mark-spin")
    val pulse by transition.animateFloat(.97f, 1.04f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mark-pulse")
    Box(Modifier.size(size.dp).clip(CircleShape).clickable(onClick = onClick).border(1.dp, UiEdge, CircleShape), contentAlignment = Alignment.Center) {
        if (active) Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin }) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, UiAccent.copy(alpha = .9f), Color.Transparent)), style = Stroke(1.6.dp.toPx()))
        }
        if (name == "Pally") {
            androidx.compose.foundation.Image(painterResource(R.drawable.pally_logo), contentDescription = "Pally", modifier = Modifier.size((size - 18).dp).scale(if (active) pulse else 1f).clip(CircleShape))
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (name == "Fo by Wajo") "Fo" else "MCP", color = UiWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (name == "Fo by Wajo") Text("by Wajo", color = UiMuted, fontSize = 8.sp)
        }
    }
}

@Composable private fun AgentChatScreen(
    assistant: String,
    connected: Boolean,
    messages: List<AgentMessage>,
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    activity: AgentActivity,
    motion: Boolean,
    note: String,
    onMenu: () -> Unit,
    onAssistant: () -> Unit,
    onVoice: () -> Unit,
    onAdd: () -> Unit,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
    onReply: (AgentMessage) -> Unit,
    onDismissNote: () -> Unit
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                RoundControl(Icons.Default.Menu, "Open your work", onMenu)
                AgentMark(assistant, activity, motion, onAssistant)
                RoundControl(Icons.Default.Call, "Open voice", onVoice)
            }
            Text("$assistant · ${if (connected) "connected" else "not connected"}", Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), color = UiMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 34.dp).offset(y = (-42).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("your conversation starts here", color = UiWhite, fontSize = 27.sp, lineHeight = 34.sp, textAlign = TextAlign.Center)
                    if (!connected) Text("Preview only. Messages stay on this screen and are not sent to an assistant.", Modifier.padding(top = 18.dp).widthIn(max = 340.dp), color = UiMuted, fontSize = 16.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = UiEdge.copy(alpha = .5f))
                Text("today", Modifier.padding(horizontal = 16.dp), color = UiMuted, fontSize = 13.sp)
                androidx.compose.material3.HorizontalDivider(Modifier.weight(1f), color = UiEdge.copy(alpha = .5f))
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 19.dp, end = 19.dp, top = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                if (messages.any { it.sample }) item { Text("sample preview · not connected", Modifier.fillMaxWidth().padding(bottom = 1.dp), color = UiMuted.copy(alpha = .72f), fontSize = 10.sp, textAlign = TextAlign.Center) }
                items(messages, key = { it.id }) { message -> MessageBubble(message, assistant, onReply) }
                if (busy) item {
                    Row(Modifier.fillMaxWidth().padding(start = 5.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        AgentMark(assistant, activity, motion, {}, size = 30)
                        Text(if (activity == AgentActivity.SEARCHING) "  searching" else "  ${assistant} is thinking", color = UiMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        if (note.isNotBlank()) Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), color = Color(0xFF171717), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, UiEdge)) {
            Row(Modifier.padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note, Modifier.weight(1f), color = UiMuted, fontSize = 11.sp, lineHeight = 15.sp)
                TextButton(onClick = onDismissNote) { Text("ok", color = UiAccent) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            RoundControl(Icons.Default.Add, "Add attachment", onAdd, 54)
            Surface(Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(30.dp), color = Color(0xFF1C1B1A), border = BorderStroke(1.dp, UiEdge)) {
                Row(Modifier.padding(start = 17.dp, end = 6.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f), textStyle = TextStyle(color = UiWhite, fontSize = 16.sp, lineHeight = 22.sp), maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }), decorationBox = { inner ->
                        if (draft.isEmpty()) Text("message $assistant", color = UiMuted, fontSize = 16.sp)
                        inner()
                    })
                    androidx.compose.material3.IconButton(onClick = onEmoji, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = "Choose emoji", tint = UiWhite, modifier = Modifier.size(22.dp))
                    }
                }
            }
            if (draft.isBlank()) RoundControl(Icons.Default.Mic, "Open voice", onVoice, 54)
            else RoundControl(Icons.AutoMirrored.Filled.Send, "Send message", onSend, 54)
        }
    }
}

@Composable private fun MessageBubble(message: AgentMessage, assistant: String, onReply: (AgentMessage) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromAssistant) Arrangement.Start else Arrangement.End) {
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth(.88f)) {
            Surface(modifier = Modifier.pointerInput(message.id) { detectTapGestures(onLongPress = { onReply(message) }) }, color = if (message.fromAssistant) UiBubble else Color(0xFF111111), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (message.fromAssistant) 5.dp else 22.dp, bottomEnd = if (message.fromAssistant) 22.dp else 5.dp), border = if (message.fromAssistant) null else BorderStroke(1.dp, UiEdge)) {
                Column(Modifier.padding(start = 15.dp, end = 14.dp, top = 13.dp, bottom = 8.dp)) {
                    message.quoted?.let { quote -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF3A3938)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(30.dp).background(UiWhite, RoundedCornerShape(2.dp)))
                        Text(quote, Modifier.padding(start = 8.dp), color = Color(0xFFD1CFCD), fontSize = 12.sp, maxLines = 2)
                    } }
                    Text(message.text, Modifier.padding(top = if (message.quoted != null) 8.dp else 0.dp), color = UiWhite, fontSize = 16.sp, lineHeight = 23.sp)
                    Row(Modifier.align(Alignment.End).padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (message.localOnly) Text("local · not sent   ", color = UiMuted, fontSize = 9.sp)
                        Text(message.time, color = UiMuted, fontSize = 10.sp)
                        if (!message.fromAssistant && !message.localOnly) Icon(Icons.Default.DoneAll, "Sent", Modifier.padding(start = 4.dp).size(15.dp), tint = Color(0xFF90C7DF))
                    }
                }
            }
        }
    }
}

@Composable private fun WorkDrawer(onClose: () -> Unit, onConnections: () -> Unit, onSettings: () -> Unit, onMcp: () -> Unit) {
    Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .62f))) {
        Column(Modifier.fillMaxHeight().fillMaxWidth(.80f).background(Color(0xFF0B0B0B)).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("our work", color = UiWhite, fontSize = 24.sp)
                TextButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = UiWhite) }
            }
            androidx.compose.material3.HorizontalDivider(color = UiEdge)
            Text("in progress", Modifier.padding(top = 24.dp, bottom = 14.dp), color = UiMuted, fontSize = 12.sp)
            Text("no active work", color = UiMuted, fontSize = 14.sp)
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 24.dp), color = UiEdge)
            Text("connect", Modifier.padding(top = 22.dp, bottom = 12.dp), color = UiMuted, fontSize = 12.sp)
            DrawerItem(Icons.Default.Hub, "assistants & tools", "manage assistant connections", onConnections)
            DrawerItem(Icons.Default.Search, "MCP server", "connect a trusted endpoint", onMcp)
            Spacer(Modifier.weight(1f))
            DrawerItem(Icons.Default.Person, "your space", "settings and profile", onSettings, Modifier.padding(bottom = 20.dp))
        }
        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClose))
    }
}

@Composable private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().padding(vertical = 5.dp), color = Color.Transparent) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = UiMuted, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) { Text(title, color = UiWhite, fontSize = 15.sp); Text(subtitle, Modifier.padding(top = 3.dp), color = UiMuted, fontSize = 11.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = UiMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable private fun ConnectionsScreen(selected: String, connectedName: String, onChoose: (String) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        ScreenHeader("assistants", selected, onBack)
        Text("choose the assistant for this conversation", color = UiMuted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        listOf("Pally" to "Official assistant · OAuth sign-in required", "Fo by Wajo" to "Wajo's single-assistant MCP profile", "Custom MCP tools" to "Inspect tools · not a chat assistant by itself").forEach { (name, detail) ->
            Surface(onClick = { onChoose(name) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(18.dp), color = UiBubble, border = BorderStroke(1.dp, if (selected == name) UiAccent else UiEdge)) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentMark(name, AgentActivity.IDLE, false, {}, 40)
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text(name + if (connectedName == name) " · connected" else "", color = UiWhite, fontSize = 16.sp)
                        Text(detail, Modifier.padding(top = 4.dp), color = UiMuted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 12.dp), color = UiBubble, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, UiEdge)) {
            Text("MCP exposes server capabilities, not universal assistant behavior. Native reactions are available only when a provider explicitly supports them; this build does not send reactions.", Modifier.padding(14.dp), color = UiMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text("back to chat", color = UiAccent) }
    }
}

@Composable private fun ScreenHeader(title: String, name: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        RoundControl(Icons.Default.Menu, "Back", back)
        Column(horizontalAlignment = Alignment.CenterHorizontally) { AgentMark(name, AgentActivity.IDLE, false, {}, 54); Text(title, Modifier.padding(top = 8.dp), color = UiWhite, fontSize = 21.sp) }
        RoundControl(Icons.Default.Close, "Close", back)
    }
}

@Composable private fun ConnectionScreen(
    assistant: String,
    endpoint: String,
    onEndpoint: (String) -> Unit,
    credential: String,
    onCredential: (String) -> Unit,
    authHeader: String,
    onAuthHeader: (String) -> Unit,
    status: String,
    error: String,
    tools: List<McpTool>,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    onOpenPally: () -> Unit
) {
    val isPally = assistant == "Pally"
    val isFo = assistant == "Fo by Wajo"
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp)) {
        ScreenHeader(if (isPally) "Pally connection" else if (isFo) "Fo by Wajo" else "MCP tools", assistant, onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            if (isPally) {
                Text("Pally MCP", color = UiWhite, fontSize = 20.sp)
                Text("Pally's official connector uses OAuth 2.1 with dynamic client registration and PKCE. This app does not yet complete the sign-in flow, so Pally messages stay on this screen.", Modifier.padding(top = 10.dp), color = UiMuted, fontSize = 13.sp, lineHeight = 19.sp)
                TextButton(onClick = onOpenPally) { Text("open Pally connection guide", color = UiAccent) }
            } else {
                Text(if (isFo) "connect Fo's assistant profile" else "connect a compatible MCP endpoint", color = UiWhite, fontSize = 18.sp)
                if (isFo) {
                    Text(WajoFoEndpoint, Modifier.padding(top = 10.dp), color = UiAccent, fontSize = 12.sp)
                    Text("Wajo documents ask_fo and get_fo_reply for this single-assistant profile. Use your Wajo API key; this live connection has not been verified against your account.", Modifier.padding(top = 6.dp), color = UiMuted, fontSize = 12.sp, lineHeight = 18.sp)
                } else {
                    OutlinedTextField(endpoint, onEndpoint, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("HTTPS MCP endpoint") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = authHeader == "X-API-Key", onClick = { onAuthHeader("X-API-Key") }, label = { Text("X-API-Key") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = authHeader == "Authorization", onClick = { onAuthHeader("Authorization") }, label = { Text("Bearer") })
                    }
                }
                OutlinedTextField(credential, onCredential, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text(if (isFo) "Wajo API key (wak_…)" else "server credential (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("Credentials are stored encrypted with Android Keystore only after a successful connection. Never paste a key into chat or commit it to the public repo.", Modifier.padding(top = 8.dp), color = UiMuted, fontSize = 11.sp, lineHeight = 16.sp)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(onClick = onConnect, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 46.dp), shape = RoundedCornerShape(24.dp), color = Color(0xFF2C2A28), border = BorderStroke(1.dp, UiEdge)) { Box(contentAlignment = Alignment.Center) { Text(if (busy) "connecting…" else "connect and inspect tools", color = UiWhite, fontSize = 12.sp) } }
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("disconnect") }
                }
                Text(status, Modifier.padding(top = 12.dp), color = if (status.startsWith("connected")) UiAccent else UiMuted, fontSize = 12.sp)
                if (error.isNotBlank()) Text(error, Modifier.padding(top = 5.dp), color = Color(0xFFFFB4A8), fontSize = 12.sp, lineHeight = 17.sp)
                if (tools.isNotEmpty()) {
                    Text("server-advertised tools · ${tools.size}", Modifier.padding(top = 18.dp, bottom = 6.dp), color = UiWhite, fontSize = 15.sp)
                    tools.take(30).forEach { tool ->
                        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = UiBubble, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, UiEdge)) {
                            Column(Modifier.padding(11.dp)) { Text(tool.name, color = UiWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium); if (tool.description.isNotBlank()) Text(tool.description, Modifier.padding(top = 4.dp), color = UiMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3) }
                        }
                    }
                    Text("The app does not execute arbitrary server tools. Generic MCP tool servers are not automatically chat assistants.", Modifier.padding(top = 8.dp), color = UiMuted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable private fun GeminiSettingsScreen(saved: Boolean, motion: Boolean, onMotion: (Boolean) -> Unit, onSave: (String) -> Unit, onErase: () -> Unit, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 22.dp)) {
        ScreenHeader("Gemini Live setup", "Pally", onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("assistant motion", color = UiWhite, fontSize = 15.sp); Text("animate Pally's thinking and search states", color = UiMuted, fontSize = 11.sp) }
                Switch(checked = motion, onCheckedChange = onMotion)
            }
            Text("Gemini Live voice", color = UiWhite, fontSize = 19.sp)
            Text("This optional key is stored encrypted on this phone. Live audio and microphone capture are not connected yet. For production, use a backend-issued short-lived token rather than shipping a long-lived API key.", Modifier.padding(top = 8.dp, bottom = 12.dp), color = UiMuted, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Text(if (saved) "key saved encrypted on this device" else "no Gemini key saved", Modifier.padding(top = 7.dp), color = UiMuted, fontSize = 11.sp)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (key.isNotBlank()) { onSave(key.trim()); key = "" } }, enabled = key.isNotBlank()) { Text("save on this device") }
                OutlinedButton(onClick = { onErase(); key = "" }) { Text("erase") }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text("done", color = UiAccent) }
    }
}

@Composable private fun VoicePreviewScreen(motion: Boolean, geminiSaved: Boolean, onSettings: () -> Unit, onBack: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voice-orb")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4700), RepeatMode.Reverse), label = "orb-phase")
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSettings) { Text("Gemini Live setup", color = UiAccent) }
            RoundControl(Icons.Default.Close, "Close voice", onBack)
        }
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(250.dp).semantics { contentDescription = "Animated voice orb preview" }) {
            val p = if (motion) phase else 0f
            val cx = size.width / 2; val cy = size.height / 2; val base = size.minDimension * .36f
            val path = Path()
            for (i in 0..180) {
                val a = i * 2.0 * Math.PI / 180.0
                val r = base * (1f + .07f * sin(a * 3 + p * 2 * Math.PI).toFloat() + .035f * cos(a * 5 - p * 2 * Math.PI).toFloat())
                val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawCircle(Color(0xFF344047).copy(alpha = .52f), radius = base * 1.25f, center = Offset(cx, cy))
            drawPath(path, Brush.radialGradient(listOf(Color(0xFFEAF3EE), Color(0xFF9DB8B9), Color(0xFF536E79), Color(0xFF26323A)), center = Offset(cx * .72f, cy * .70f), radius = base * 2f))
            drawPath(path, Color.White.copy(alpha = .38f), style = Stroke(1.2.dp.toPx()))
        }
        Spacer(Modifier.height(32.dp))
        Text("voice", color = UiWhite, fontSize = 24.sp)
        Text(if (geminiSaved) "Gemini key saved locally. Live voice is not connected in this build." else "set up an optional Gemini API key in preferences", Modifier.padding(top = 10.dp), color = UiMuted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        Text("microphone and call session are preview-only", Modifier.padding(top = 6.dp), color = UiMuted.copy(alpha = .76f), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onBack, modifier = Modifier.padding(bottom = 16.dp)) { Text("close", color = UiAccent) }
    }
}

@Composable private fun EmojiSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = UiBubble,
        title = { Text("choose emoji", color = UiWhite) },
        text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("😊", "👍", "❤️", "✨", "🙏").forEach { emoji -> Text(emoji, Modifier.clickable { onPick(emoji) }.padding(7.dp), fontSize = 24.sp) }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("close", color = UiAccent) } })
}

private fun sampleConversation() = listOf(
    AgentMessage(text = "i can help you think it through.\nwhat matters most right now?", fromAssistant = true, sample = true, time = "1:40 am"),
    AgentMessage(text = "compare a few options for this weekend", fromAssistant = false, quoted = "you: can you help me choose?", sample = true, time = "1:41 am"),
    AgentMessage(text = "sure. tell me what you want to optimize for, and i'll keep the trade-offs clear.", fromAssistant = true, sample = true, time = "1:41 am"),
    AgentMessage(text = "keep it simple, and call out the biggest difference", fromAssistant = false, sample = true, time = "1:42 am"),
    AgentMessage(text = "got it. i'll keep the comparison short and easy to scan.", fromAssistant = true, sample = true, time = "1:42 am")
)

private fun loadChatHistory(context: Context, assistant: String): List<AgentMessage> {
    val encoded = SecretVault.read(context, "chat_history:$assistant") ?: return emptyList()
    return try {
        val rows = JSONArray(encoded)
        buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val text = row.optString("text")
                if (text.isBlank()) continue
                add(AgentMessage(
                    id = row.optString("id").ifBlank { UUID.randomUUID().toString() },
                    text = text,
                    fromAssistant = row.optBoolean("fromAssistant"),
                    quoted = row.optString("quoted").takeIf { row.has("quoted") && it.isNotBlank() },
                    localOnly = row.optBoolean("localOnly"),
                    time = row.optString("time").ifBlank { SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date()) }
                ))
            }
        }
    } catch (_: Exception) {
        SecretVault.clear(context, "chat_history:$assistant")
        emptyList()
    }
}

private fun saveChatHistory(context: Context, assistant: String, messages: List<AgentMessage>) {
    val rows = JSONArray()
    messages.filterNot { it.sample }.takeLast(200).forEach { message ->
        val row = JSONObject()
            .put("id", message.id)
            .put("text", message.text)
            .put("fromAssistant", message.fromAssistant)
            .put("localOnly", message.localOnly)
            .put("time", message.time)
        message.quoted?.let { row.put("quoted", it) }
        rows.put(row)
    }
    SecretVault.save(context, "chat_history:$assistant", rows.toString())
}

@Composable private fun AttachmentNotice(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = UiBubble, title = { Text("add to message", color = UiWhite) }, text = {
        Column { listOf("camera", "photos", "files", "location").forEach { item -> Text(item, Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 12.dp), color = UiWhite) } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("close", color = UiAccent) } })
}
