package com.ichiro.pallyagent

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val PREFS = "agent_state"
private const val ASSISTANT_PREF = "selected_assistant"
private const val MOTION_PREF = "assistant_motion"
private const val TEXT_PREF = "text_size"
private const val SHEET_ATTACH = "attachments"
private const val SHEET_EMOJI = "emoji"
private val TEXT_SIZE_LABELS = listOf("compact", "comfortable", "large")

private fun textScaleFor(index: Int): Float = when (index) {
    0 -> .9f
    2 -> 1.15f
    else -> 1f
}

class PallyIntegratedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: the root container pads for system bars and the keyboard (see IntegratedApp).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        intent?.data?.let { PallyOAuthCallback.events.trySend(it) }
        setContent {
            PallyTheme {
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
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    // Respect the system "remove animations" setting (animator duration scale 0) in addition to the in-app switch.
    val systemAnimationsOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val pages = remember { mutableStateListOf(Page.CHAT) }
    val page = pages.last()
    var assistant by remember { mutableStateOf(prefs.getString(ASSISTANT_PREF, PALLY) ?: PALLY) }
    var motionOn by remember { mutableStateOf(prefs.getBoolean(MOTION_PREF, true)) }
    val motion = motionOn && !systemAnimationsOff
    var textSize by remember {
        mutableIntStateOf(prefs.getInt(TEXT_PREF, 1).coerceIn(0, TEXT_SIZE_LABELS.lastIndex))
    }
    var lines by remember { mutableStateOf<List<Line>>(emptyList()) }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("not connected") }
    var endpoint by remember { mutableStateOf(FO_URL) }
    var serverName by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var authMethod by remember { mutableStateOf(AUTH_HEADER) }
    var client by remember { mutableStateOf<McpClient?>(null) }
    var clientName by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    // Written from the adapters on a background thread; read here as composable state.
    val stageFlow = remember { MutableStateFlow(ReplyStage.NONE) }
    val stage by stageFlow.collectAsState()
    var drawer by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf("") }
    var geminiSaved by remember { mutableStateOf(SecretVault.read(context, GEMINI_KEY) != null) }
    var foSaved by remember { mutableStateOf(SecretVault.read(context, FO_KEY) != null) }
    val connected = client != null && clientName == assistant

    fun navigate(to: Page) {
        drawer = false
        sheet = ""
        if (pages.last() != to) pages.add(to)
    }

    fun back() {
        drawer = false
        sheet = ""
        if (pages.size > 1) pages.removeAt(pages.lastIndex)
    }

    fun resetTo(to: Page) {
        drawer = false
        sheet = ""
        pages.clear()
        pages.add(to)
    }

    suspend fun attachPally(token: String) {
        val live = McpClient(PallyOAuth.MCP_ENDPOINT, token, "Authorization")
        val advertised = live.connectAndListTools()
        check(advertised.any { it.name == "message_pally" }) { "Pally did not advertise message_pally." }
        client = live
        clientName = PALLY
        tools = advertised
        status = "connected · ${advertised.size} tools"
        error = ""
    }

    fun selectAssistant(name: String) {
        assistant = name
        prefs.edit().putString(ASSISTANT_PREF, name).apply()
        if (name == FO) {
            endpoint = FO_URL
            authMethod = AUTH_HEADER
        }
        if (clientName != name) status = "not connected"
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
                resetTo(Page.CHAT)
                note = "Pally is connected on this device."
            } catch (e: Exception) {
                status = "sign-in failed"
                error = e.message ?: "Could not finish Pally sign-in."
            } finally {
                busy = false
            }
        }
    }

    fun connect() {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                if (assistant == PALLY) {
                    val token = withContext(Dispatchers.IO) { PallyOAuth.accessToken(context) }
                    if (token != null) {
                        attachPally(token)
                    } else {
                        val url = withContext(Dispatchers.IO) { PallyOAuth.begin(context) }
                        status = "waiting for Pally approval in browser…"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                } else {
                    val target = if (assistant == FO) FO_URL else endpoint.trim()
                    val header = if (assistant == FO || authMethod != AUTH_BEARER) AUTH_HEADER else AUTH_BEARER
                    val key = when {
                        assistant != FO && authMethod == AUTH_NONE -> ""
                        secret.isNotBlank() -> secret.trim()
                        assistant == FO -> SecretVault.read(context, FO_KEY).orEmpty()
                        else -> ""
                    }
                    require(target.startsWith("https://")) { "Use an HTTPS MCP endpoint." }
                    if (assistant == FO) require(key.startsWith("wak_")) { "Enter your Wajo API key (wak_…)." }
                    status = "connecting…"
                    val live = McpClient(target, key, header)
                    val advertised = withContext(Dispatchers.IO) { live.connectAndListTools() }
                    if (assistant == FO) {
                        check(advertised.any { it.name == "ask_fo" }) { "This endpoint did not advertise ask_fo, so it is not the documented Fo profile." }
                        withContext(Dispatchers.IO) { SecretVault.save(context, FO_KEY, key) }
                        foSaved = true
                    }
                    client = live
                    clientName = assistant
                    tools = advertised
                    secret = ""
                    status = "connected · ${advertised.size} tools"
                    if (assistant == CUSTOM) navigate(Page.REVIEW_ACCESS)
                }
            } catch (e: Exception) {
                status = "connection failed"
                error = e.message ?: "Could not connect."
            } finally {
                busy = false
            }
        }
    }

    fun send() {
        val text = draft.trim()
        if (text.isBlank() || busy || loadedFor != assistant) return
        if (lines.any { it.sample }) lines = emptyList()
        val quoted = quote
        quote = null
        draft = ""
        if (!connected || assistant !in listOf(PALLY, FO)) {
            lines = lines + Line(text = text, assistant = false, local = true, quote = quoted)
            note = "saved on this device only · connect $assistant to send messages"
            return
        }
        lines = lines + Line(text = text, assistant = false, quote = quoted)
        busy = true
        scope.launch {
            stageFlow.value = ReplyStage.SENT
            try {
                val onStage: (ReplyStage) -> Unit = { stageFlow.value = it }
                val answer = if (assistant == PALLY) {
                    val token = PallyOAuth.accessToken(context) ?: error("Pally sign-in expired. Reconnect to continue.")
                    val live = McpClient(PallyOAuth.MCP_ENDPOINT, token, "Authorization")
                    val advertised = withContext(Dispatchers.IO) { live.connectAndListTools() }
                    check(advertised.any { it.name == "message_pally" }) { "Pally did not advertise message_pally." }
                    client = live
                    tools = advertised
                    withContext(Dispatchers.IO) { PallyAdapter.send(live, advertised, text, onStage) }
                } else {
                    val fo = client ?: error("Reconnect to Fo first.")
                    val knownTools = tools
                    withContext(Dispatchers.IO) { FoAdapter.send(fo, knownTools, text, onStage) }
                }
                lines = lines + Line(text = answer, assistant = true)
            } catch (e: Exception) {
                lines = lines + Line(text = "I couldn't get a reply from $assistant: ${e.message ?: "connection error"}", assistant = true)
            } finally {
                busy = false
                stageFlow.value = ReplyStage.NONE
            }
        }
    }

    fun eraseHistory() {
        listOf(PALLY, FO, CUSTOM).forEach { SecretVault.clear(context, "chat_history:$it") }
        lines = emptyList()
        note = "conversation history erased on this device"
    }

    val workItems = if (busy) listOf(WorkItem(title = "reply from $assistant", detail = stageLabel(stage))) else emptyList()

    BackHandler(enabled = pages.size > 1 || drawer || sheet.isNotEmpty()) {
        when {
            sheet.isNotEmpty() -> sheet = ""
            drawer -> drawer = false
            else -> back()
        }
    }

    Box(Modifier.fillMaxSize().background(PallyColor.Ink)) {
        Scanlines(Modifier.fillMaxSize())
        // One padding source for status bar, navigation bar and keyboard, so nothing is covered twice.
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Crossfade(targetState = page, animationSpec = tween(PallyMotion.FadeMs), label = "page") { current ->
                when (current) {
                    Page.CHAT -> PallyChatScreen(
                        assistant = assistant,
                        connected = connected,
                        lines = lines,
                        draft = draft,
                        onDraft = { draft = it },
                        busy = busy,
                        stage = stage,
                        motion = motion,
                        textScale = textScaleFor(textSize),
                        note = note,
                        onDismissNote = { note = "" },
                        quote = quote,
                        onClearQuote = { quote = null },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onVoice = { navigate(Page.VOICE) },
                        onAdd = { sheet = SHEET_ATTACH },
                        onEmoji = { sheet = SHEET_EMOJI },
                        onSend = { send() },
                        onReply = { line -> quote = if (line.assistant) "$assistant: ${line.text}" else "you: ${line.text}" }
                    )
                    Page.ASSISTANTS -> AssistantsScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        pallyConnected = client != null && clientName == PALLY,
                        foSaved = foSaved,
                        customConnected = client != null && clientName == CUSTOM,
                        onSelect = { selectAssistant(it) },
                        onAdd = { navigate(Page.ADD_ASSISTANT) },
                        onMcp = {
                            selectAssistant(CUSTOM)
                            navigate(Page.MCP_SETUP)
                        },
                        onManage = { navigate(if (assistant == CUSTOM) Page.MCP_SETUP else Page.CONNECT) },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() }
                    )
                    Page.ADD_ASSISTANT -> AddAssistantScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        onPick = { name ->
                            selectAssistant(name)
                            navigate(Page.CONNECT)
                        },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() }
                    )
                    Page.CONNECT -> ConnectScreen(
                        name = assistant,
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        secret = secret,
                        onSecret = { secret = it },
                        status = status,
                        error = error,
                        tools = tools,
                        onConnect = { connect() },
                        onDisconnect = {
                            client = null
                            clientName = ""
                            tools = emptyList()
                            if (assistant == PALLY) PallyOAuth.disconnect(context)
                            if (assistant == FO) {
                                SecretVault.clear(context, FO_KEY)
                                foSaved = false
                            }
                            status = "disconnected"
                            error = "Saved credentials for $assistant were erased from this device."
                        },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() }
                    )
                    Page.MCP_SETUP -> McpSetupScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        serverName = serverName,
                        onServerName = { serverName = it },
                        endpoint = endpoint,
                        onEndpoint = { endpoint = it },
                        method = authMethod,
                        onMethod = { authMethod = it },
                        secret = secret,
                        onSecret = { secret = it },
                        error = error,
                        onContinue = { connect() },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() }
                    )
                    Page.REVIEW_ACCESS -> ReviewAccessScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        serverName = serverName.ifBlank { "MCP server" },
                        tools = tools,
                        onDone = { resetTo(Page.ASSISTANTS) },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() }
                    )
                    Page.VOICE -> PallyVoiceScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        saved = geminiSaved,
                        onSetup = { navigate(Page.VOICE_SETUP) },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() }
                    )
                    Page.VOICE_SETUP -> VoiceSetupScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        saved = geminiSaved,
                        onSave = { value ->
                            SecretVault.save(context, GEMINI_KEY, value)
                            geminiSaved = true
                        },
                        onErase = {
                            SecretVault.clear(context, GEMINI_KEY)
                            geminiSaved = false
                        },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() },
                        onDone = { back() }
                    )
                    Page.PREFERENCES -> PreferencesScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        motionOn = motionOn,
                        onMotion = { value ->
                            motionOn = value
                            prefs.edit().putBoolean(MOTION_PREF, value).apply()
                        },
                        reduceMotion = systemAnimationsOff,
                        textSizeLabel = TEXT_SIZE_LABELS[textSize],
                        onTextSize = {
                            textSize = (textSize + 1) % TEXT_SIZE_LABELS.size
                            prefs.edit().putInt(TEXT_PREF, textSize).apply()
                        },
                        onAssistant = { navigate(Page.ASSISTANTS) },
                        onVoice = { navigate(Page.VOICE_SETUP) },
                        onPrivacy = { navigate(Page.PRIVACY) },
                        onHelp = { navigate(Page.HELP) },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() },
                        onDone = { back() }
                    )
                    Page.PRIVACY -> PrivacyScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        onErase = { eraseHistory() },
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() },
                        onDone = { back() }
                    )
                    Page.HELP -> HelpScreen(
                        assistant = assistant,
                        connected = connected,
                        busy = busy,
                        motion = motion,
                        onMenu = { drawer = true },
                        onMark = { navigate(Page.ASSISTANTS) },
                        onClose = { back() },
                        onDone = { back() }
                    )
                }
            }

            AnimatedVisibility(
                visible = drawer,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(PallyMotion.FadeMs)) + slideInHorizontally(tween(PallyMotion.FadeMs)) { -it / 4 },
                exit = fadeOut(tween(PallyMotion.FadeMs)) + slideOutHorizontally(tween(PallyMotion.FadeMs)) { -it / 4 }
            ) {
                PallyWorkDrawer(
                    items = workItems,
                    onClose = { drawer = false },
                    onAssistants = { navigate(Page.ASSISTANTS) },
                    onSettings = { navigate(Page.PREFERENCES) }
                )
            }
            if (sheet == SHEET_ATTACH) PallyAttachmentSheet(onDismiss = { sheet = "" })
            if (sheet == SHEET_EMOJI) {
                PallyEmojiSheet(
                    onPick = { emoji ->
                        draft += emoji
                        sheet = ""
                    },
                    onDismiss = { sheet = "" }
                )
            }
        }
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
