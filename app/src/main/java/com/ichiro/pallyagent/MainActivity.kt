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
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF080808)
private val Card = Color(0xFF1B1B1B)
private val Edge = Color(0xFF3A3A3A)
private val White = Color(0xFFF2F1F0)
private val Dim = Color(0xFFA09F9E)
private val Accent = Color(0xFFBDCEC8)
private const val WajoFoEndpoint = "https://mcp.api.wajo.ai/fo"
private const val PallyEndpoint = "https://agent.pally.com/mcp"
private const val MCPSecret = "wajo_mcp_api_key"
private const val EndpointPref = "mcp_endpoint"

private enum class Page { CHAT, WORK, ASSISTANTS, MCP, SETTINGS, VOICE }
private enum class AgentState { IDLE, THINKING, SEARCHING }
private data class Line(val id: String = UUID.randomUUID().toString(), val text: String, val outgoing: Boolean, val label: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        val systemMotion = try {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Exception) { true }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Ink, surface = Card, onSurface = White)) {
                AgentApp(systemMotion)
            }
        }
    }
}

@Composable private fun AgentApp(systemMotion: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.CHAT) }
    var selected by remember { mutableStateOf("Pally") }
    var motion by remember { mutableStateOf(systemMotion) }
    var animationPreview by remember { mutableStateOf(AgentState.IDLE) }
    val messages = remember { mutableStateListOf<Line>() }
    var draft by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(context.getSharedPreferences("pally_agent_settings", Context.MODE_PRIVATE).getString(EndpointPref, WajoFoEndpoint) ?: WajoFoEndpoint) }
    var credential by remember { mutableStateOf("") }
    var credentialHeader by remember { mutableStateOf("X-API-Key") }
    var connectionStatus by remember { mutableStateOf("not connected") }
    var connectionError by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf(false) }
    var activeClient by remember { mutableStateOf<McpClient?>(null) }
    var activeAssistant by remember { mutableStateOf("") }
    var connectedTools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var savedGeminiKey by remember { mutableStateOf(SecretVault.read(context, "gemini_live_key") != null) }
    val hasCurrentConnection = activeClient != null && activeAssistant == selected

    BackHandler(page != Page.CHAT || attachments) {
        if (attachments) attachments = false else page = Page.CHAT
    }
    LaunchedEffect(animationPreview) {
        if (animationPreview != AgentState.IDLE) { delay(3500); animationPreview = AgentState.IDLE }
    }

    fun connectMcp() {
        if (selected == "Pally") {
            connectionError = "Pally's official connector uses OAuth 2.1 with dynamic registration and PKCE. This preview does not finish that browser flow yet."
            connectionStatus = "OAuth setup required"
            return
        }
        val chosenEndpoint = if (selected == "Fo by Wajo") WajoFoEndpoint else endpoint.trim()
        val chosenKey = credential.trim().ifBlank { SecretVault.read(context, MCPSecret).orEmpty() }
        if (chosenEndpoint.isBlank()) { connectionError = "Enter an HTTPS MCP endpoint."; return }
        if (selected == "Fo by Wajo" && !chosenKey.startsWith("wak_")) {
            connectionError = "Wajo's documented API key starts with wak_. Enter it in the app; do not send it in chat."
            return
        }
        connectionError = ""
        connectionStatus = "connecting…"
        working = true
        scope.launch {
            try {
                val client = McpClient(chosenEndpoint, chosenKey, credentialHeader)
                val found = client.connectAndListTools()
                if (selected == "Fo by Wajo" && found.none { it.name == "ask_fo" }) {
                    throw IllegalStateException("The server connected but did not expose ask_fo, so it is not the Wajo Fo profile.")
                }
                activeClient = client
                activeAssistant = selected
                connectedTools = found
                context.getSharedPreferences("pally_agent_settings", Context.MODE_PRIVATE).edit().putString(EndpointPref, chosenEndpoint).apply()
                if (chosenKey.isNotBlank()) SecretVault.save(context, MCPSecret, chosenKey)
                credential = ""
                connectionStatus = "connected · ${found.size} tools"
            } catch (e: Exception) {
                activeClient = null
                activeAssistant = ""
                connectedTools = emptyList()
                connectionStatus = "connection failed"
                connectionError = e.message ?: "Could not connect to the MCP server."
            } finally { working = false }
        }
    }

    fun disconnectMcp() {
        activeClient = null
        activeAssistant = ""
        connectedTools = emptyList()
        SecretVault.clear(context, MCPSecret)
        connectionStatus = "disconnected"
        connectionError = "The saved Wajo key was erased from this device."
    }

    fun sendMessage() {
        val text = draft.trim()
        if (text.isBlank() || working) return
        draft = ""
        if (selected == "Fo by Wajo" && hasCurrentConnection) {
            messages.add(Line(text = text, outgoing = true, label = "sent to Fo"))
            working = true
            animationPreview = AgentState.THINKING
            scope.launch {
                try {
                    val client = activeClient ?: throw IllegalStateException("Reconnect to Fo before sending.")
                    val reply = FoAdapter.send(client, connectedTools, text)
                    messages.add(Line(text = reply, outgoing = false, label = "Fo by Wajo"))
                } catch (e: Exception) {
                    messages.add(Line(text = "I couldn't get a reply from Fo: ${e.message ?: "connection error"}", outgoing = false, label = "connection issue"))
                } finally {
                    working = false
                    animationPreview = AgentState.IDLE
                }
            }
        } else {
            messages.add(Line(text = text, outgoing = true, label = "local only · not sent"))
            connectionError = if (selected == "Pally") "Pally is not connected in this preview. Its OAuth flow is not implemented yet." else "Connect Fo by Wajo to send a live assistant message."
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Scanlines(Modifier.matchParentSize())
        when (page) {
            Page.CHAT -> Chat(
                name = selected,
                messages = messages,
                input = draft,
                onInput = { draft = it },
                state = animationPreview,
                motion = motion,
                connected = hasCurrentConnection,
                working = working,
                note = connectionError,
                onMenu = { page = Page.WORK },
                onCall = { page = Page.VOICE },
                onBrand = { page = Page.ASSISTANTS },
                onAdd = { attachments = true },
                onSend = { sendMessage() }
            )
            Page.WORK -> Work({ page = Page.CHAT }, { page = Page.ASSISTANTS }, { page = Page.SETTINGS })
            Page.ASSISTANTS -> Assistants(
                selected = selected,
                connectedAssistant = activeAssistant,
                onChoose = { name ->
                    selected = name
                    if (name == "Fo by Wajo") { endpoint = WajoFoEndpoint; credentialHeader = "X-API-Key" }
                    if (name == "Pally") endpoint = PallyEndpoint
                    page = Page.MCP
                },
                back = { page = Page.CHAT }
            )
            Page.MCP -> McpSetup(
                selected = selected,
                endpoint = endpoint,
                onEndpoint = { endpoint = it },
                credential = credential,
                onCredential = { credential = it },
                credentialHeader = credentialHeader,
                onCredentialHeader = { credentialHeader = it },
                status = connectionStatus,
                error = connectionError,
                tools = if (activeAssistant == selected) connectedTools else emptyList(),
                busy = working,
                onConnect = { connectMcp() },
                onDisconnect = { disconnectMcp() },
                onOpenDocs = {
                    val url = if (selected == "Pally") "https://pally.com/mcp" else "https://wajo.ai/docs/mcp-server.md"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                },
                back = { page = Page.ASSISTANTS }
            )
            Page.SETTINGS -> Preferences(
                motion = motion,
                onMotion = { motion = it },
                savedGeminiKey = savedGeminiKey,
                onSaveGeminiKey = { value ->
                    SecretVault.save(context, "gemini_live_key", value.trim())
                    savedGeminiKey = value.isNotBlank()
                },
                onClearGeminiKey = { SecretVault.clear(context, "gemini_live_key"); savedGeminiKey = false },
                onBack = { page = Page.CHAT },
                onPreview = { animationPreview = it; page = Page.CHAT }
            )
            Page.VOICE -> Voice(motion, onSettings = { page = Page.SETTINGS }, onBack = { page = Page.CHAT })
        }
    }
    if (attachments) AlertDialog(
        onDismissRequest = { attachments = false },
        title = { Text("Attachments") },
        text = { Text("File and image attachments are not implemented in this build.") },
        confirmButton = { TextButton(onClick = { attachments = false }) { Text("OK") } }
    )
}

@Composable private fun Scanlines(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .04f), Color.White.copy(alpha = .045f), Color.Transparent))
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

@Composable private fun Brand(name: String, state: AgentState, motion: Boolean, click: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "assistant motion")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "ring")
    val pulse by transition.animateFloat(.96f, 1.05f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(62.dp).clip(CircleShape).clickable(onClick = click).border(1.dp, Edge, CircleShape).semantics { contentDescription = "Selected assistant $name, open assistants" }, contentAlignment = Alignment.Center) {
        if (state != AgentState.IDLE) Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = if (motion) angle else 0f }) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Accent, Color.Transparent)), style = Stroke(1.5.dp.toPx()))
        }
        if (name == "Pally") Image(
            painter = painterResource(R.drawable.pally_logo),
            contentDescription = "Pally logo",
            modifier = Modifier.size(45.dp).scale(if (motion && state != AgentState.IDLE) pulse else 1f)
                .graphicsLayer { rotationZ = if (motion && state == AgentState.SEARCHING) angle else 0f }
                .clip(CircleShape)
        ) else Text(if (name == "Fo by Wajo") "Fo" else "M", color = White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun TopBar(name: String, state: AgentState, motion: Boolean, menu: () -> Unit, call: () -> Unit, brand: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        CircleControl(Icons.Default.Menu, "Open your work", menu)
        Brand(name, state, motion, brand)
        CircleControl(Icons.Default.Call, "Open voice", call)
    }
}

@Composable private fun Chat(
    name: String, messages: SnapshotStateList<Line>, input: String, onInput: (String) -> Unit,
    state: AgentState, motion: Boolean, connected: Boolean, working: Boolean, note: String,
    onMenu: () -> Unit, onCall: () -> Unit, onBrand: () -> Unit, onAdd: () -> Unit, onSend: () -> Unit
) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopBar(name, state, motion, onMenu, onCall, onBrand)
        val subtitle = when {
            connected -> "$name · connected"
            name == "Custom MCP tools" -> "MCP tools · not connected"
            else -> "$name · not connected"
        }
        Text(subtitle, Modifier.fillMaxWidth(), color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("your conversation starts here", color = White, fontSize = 23.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (connected && name == "Fo by Wajo") "Fo is connected. Send a message to start a real conversation." else "Connect an assistant to send a message. Unconnected messages stay on this device.",
                    color = Dim, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center
                )
            }
        } else LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.outgoing) Color(0xFF111111) else Card, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Edge), modifier = Modifier.widthIn(max = 310.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                            Text(message.text, color = White, fontSize = 16.sp, lineHeight = 23.sp)
                            Text(message.label, Modifier.align(Alignment.End).padding(top = 6.dp), color = Dim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        if (working) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Accent)
            Text("  ${if (name == "Fo by Wajo") "Fo is replying" else "connecting"}…", color = Dim, fontSize = 12.sp)
        } else if (state != AgentState.IDLE) Text(if (state == AgentState.THINKING) "thinking" else "searching", Modifier.fillMaxWidth().padding(bottom = 8.dp), color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
        if (note.isNotBlank()) Text(note, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), color = Dim, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center, maxLines = 3)
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleControl(Icons.Default.Add, "Attachments unavailable", onAdd)
            TextField(
                value = input, onValueChange = onInput, modifier = Modifier.weight(1f).heightIn(min = 48.dp), maxLines = 4,
                placeholder = { Text("message $name", color = Dim, fontSize = 15.sp) }, shape = RoundedCornerShape(27.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Card, unfocusedContainerColor = Card, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Accent)
            )
            CircleControl(Icons.AutoMirrored.Filled.Send, if (connected) "Send to $name" else "Add local message", onSend)
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
        Text("Your assistant connections and app preferences", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        RowCard("assistants & tools", "Choose Pally or connect Fo by Wajo through MCP", connections)
        RowCard("preferences", "Motion, animation and Gemini Live key settings", settings)
    }
}

@Composable private fun Assistants(selected: String, connectedAssistant: String, onChoose: (String) -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenHeader("assistants", back)
        Text("Choose a chat assistant or connect a separate MCP tool server", color = Dim, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))
        RowCard("Pally${if (selected == "Pally") "  · selected" else ""}", "Official MCP endpoint · OAuth 2.1 PKCE flow is not implemented in this build") { onChoose("Pally") }
        RowCard("Fo by Wajo${if (selected == "Fo by Wajo") "  · selected" else ""}", if (connectedAssistant == "Fo by Wajo") "Connected · open settings to inspect tools" else "Wajo's dedicated Fo profile · connect using your Wajo API key") { onChoose("Fo by Wajo") }
        RowCard("Custom MCP tools${if (selected == "Custom MCP tools") "  · selected" else ""}", "Connect an HTTPS MCP server and inspect its advertised tools; tools are not chat assistants") { onChoose("Custom MCP tools") }
        Text("Only Fo chat is wired to a live assistant in this build. MCP tools are not automatically safe or universally compatible. Native message reactions depend on provider support and are not enabled here.", Modifier.padding(top = 18.dp), color = Dim, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable private fun McpSetup(
    selected: String, endpoint: String, onEndpoint: (String) -> Unit,
    credential: String, onCredential: (String) -> Unit,
    credentialHeader: String, onCredentialHeader: (String) -> Unit,
    status: String, error: String, tools: List<McpTool>, busy: Boolean,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onOpenDocs: () -> Unit, back: () -> Unit
) {
    val isPally = selected == "Pally"
    val isFo = selected == "Fo by Wajo"
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenHeader(if (isPally) "Pally connection" else if (isFo) "Fo by Wajo" else "MCP tools", back)
        when {
            isPally -> {
                Text("Pally MCP", color = White, fontSize = 20.sp)
                Text("Official endpoint: $PallyEndpoint\nPally documents OAuth 2.1 with dynamic client registration and PKCE. This build does not complete that sign-in flow, so it cannot connect Pally yet.", Modifier.padding(vertical = 14.dp), color = Dim, fontSize = 13.sp, lineHeight = 20.sp)
                OutlinedButton(onClick = onOpenDocs, modifier = Modifier.fillMaxWidth()) { Text("open Pally connection guide") }
            }
            else -> {
                Text(if (isFo) "Connect Fo's dedicated MCP profile" else "Connect a compatible HTTPS MCP server", color = White, fontSize = 20.sp)
                Text(
                    if (isFo) "Wajo documents https://mcp.api.wajo.ai/fo as a single-assistant profile with ask_fo and get_fo_reply. A Wajo API key may be sent in X-API-Key or Authorization: Bearer.",
                    Modifier.padding(vertical = 12.dp), color = Dim, fontSize = 12.sp, lineHeight = 18.sp
                )
                if (isFo) {
                    Text("$WajoFoEndpoint", color = Accent, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                } else {
                    OutlinedTextField(value = endpoint, onValueChange = onEndpoint, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTPS MCP endpoint") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auth header", color = Dim, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        FilterChip(selected = credentialHeader == "X-API-Key", onClick = { onCredentialHeader("X-API-Key") }, label = { Text("X-API-Key") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = credentialHeader == "Authorization", onClick = { onCredentialHeader("Authorization") }, label = { Text("Bearer") })
                    }
                }
                OutlinedTextField(
                    value = credential, onValueChange = onCredential, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (isFo) "Wajo API key (wak_…)" else "Server credential (optional)") },
                    placeholder = { Text("leave blank to reuse saved device key", color = Dim) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Text("Credentials are encrypted with Android Keystore on this device and are not sent to this chat or included in the public source. Only use a server you trust.", Modifier.padding(top = 8.dp), color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConnect, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "connecting…" else "connect and inspect tools") }
                    OutlinedButton(onClick = onDisconnect, enabled = !busy) { Text("disconnect") }
                }
                Text("$status", Modifier.padding(top = 12.dp), color = if (status.startsWith("connected")) Accent else Dim, fontSize = 12.sp)
                if (error.isNotBlank()) Text(error, Modifier.padding(top = 6.dp), color = Color(0xFFFFB4A8), fontSize = 12.sp, lineHeight = 17.sp)
                if (tools.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text("server-advertised tools · ${tools.size}", color = White, fontSize = 15.sp)
                    tools.take(30).forEach { tool ->
                        Surface(Modifier.fillMaxWidth().padding(top = 7.dp), color = Card, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(tool.name, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (tool.description.isNotBlank()) Text(tool.description, Modifier.padding(top = 4.dp), color = Dim, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3)
                            }
                        }
                    }
                    Text("This build lists tools but does not run arbitrary MCP tools. Tool invocation needs schema-aware review and an explicit approval step.", Modifier.padding(top = 12.dp), color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
                }
                if (isFo) Text("Wajo describes Fo as able to act through the owner's Wajo account. Use Fo's own approvals for any real-world action. This preview does not provide native message reactions.", Modifier.padding(top = 14.dp), color = Dim, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable private fun Preferences(
    motion: Boolean, onMotion: (Boolean) -> Unit, savedGeminiKey: Boolean,
    onSaveGeminiKey: (String) -> Unit, onClearGeminiKey: () -> Unit,
    onBack: () -> Unit, onPreview: (AgentState) -> Unit
) {
    var geminiKey by remember { mutableStateOf("") }
    var savedLabel by remember { mutableStateOf(if (savedGeminiKey) "A key is encrypted on this device." else "No Gemini key saved.") }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenHeader("preferences", onBack)
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text("assistant animations", Modifier.weight(1f), color = White); Switch(checked = motion, onCheckedChange = onMotion) }
        RowCard("preview thinking", "See the Pally mark's thinking animation") { onPreview(AgentState.THINKING) }
        RowCard("preview searching", "See the Pally mark's searching animation") { onPreview(AgentState.SEARCHING) }
        Spacer(Modifier.height(20.dp))
        Text("Gemini Live key", color = White, fontSize = 19.sp)
        Text("Optional local key storage only. This build does not connect Live audio yet. Google recommends short-lived ephemeral tokens from your own authenticated backend for a production mobile client; long-lived keys in client apps can be extracted.", Modifier.padding(vertical = 9.dp), color = Dim, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedTextField(value = geminiKey, onValueChange = { geminiKey = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Text(savedLabel, Modifier.padding(top = 7.dp), color = Dim, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            Button(onClick = { if (geminiKey.isNotBlank()) { onSaveGeminiKey(geminiKey); geminiKey = ""; savedLabel = "Key encrypted on this device. Live voice is not active." } }, enabled = geminiKey.isNotBlank()) { Text("save on device") }
            OutlinedButton(onClick = { onClearGeminiKey(); geminiKey = ""; savedLabel = "Saved key erased." }) { Text("erase key") }
        }
        Text("No Gemini connection, API requests, or microphone audio are implemented yet.", Modifier.padding(top = 10.dp), color = Dim, fontSize = 11.sp)
    }
}

@Composable private fun Voice(motion: Boolean, onSettings: () -> Unit, onBack: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voice orb")
    val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "orb phase")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onSettings) { Text("Gemini Live setup", color = Accent) }
            CircleControl(Icons.Default.Close, "Close voice", onBack)
        }
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
        Text("The orb animates, but microphone audio and Gemini Live are not connected in this build.", Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 19.sp, color = Dim, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("close") }
    }
}
