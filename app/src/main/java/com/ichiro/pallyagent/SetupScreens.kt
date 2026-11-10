package com.ichiro.pallyagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Authentication choices for a generic MCP server. "none" sends no credential at all. */
internal const val AUTH_HEADER = "X-API-Key"
internal const val AUTH_BEARER = "Authorization"
internal const val AUTH_NONE = "none"

/** Assistant chooser: the default assistant, saved providers, and connection types. */
@Composable
internal fun AssistantsScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    pallyConnected: Boolean,
    foSaved: Boolean,
    customConnected: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onMcp: () -> Unit,
    onManage: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit
) {
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = { PallyPill(text = "manage connections", onClick = onManage) }
    ) {
        PageTitle("assistants", "Choose the assistant for this conversation")
        AssistantCard(
            name = PALLY,
            detail = "default assistant · " + if (pallyConnected) "connected" else "sign in needed",
            selected = assistant == PALLY,
            onClick = { onSelect(PALLY) }
        )
        if (foSaved) {
            AssistantCard(
                name = FO,
                detail = "Wajo profile · API key saved",
                selected = assistant == FO,
                onClick = { onSelect(FO) }
            )
        }
        if (customConnected) {
            AssistantCard(
                name = CUSTOM,
                detail = "MCP server · tools listed, not a chat assistant",
                selected = assistant == CUSTOM,
                onClick = { onSelect(CUSTOM) }
            )
        }
        SectionLabel("available connection types")
        SettingRow(icon = Icons.Default.Add, title = "Add an assistant", subtitle = "select a supported provider", onClick = onAdd)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = PallyColor.Divider)
        SettingRow(
            icon = Icons.Default.Link,
            title = "Connect an MCP server",
            subtitle = "where supported by the service",
            onClick = onMcp
        )
        Spacer(Modifier.height(18.dp))
        PanelCard {
            Text("Connections depend on each provider.", color = PallyColor.Text, fontSize = PallyType.Row)
            Text(
                "Pally signs in through your browser. Fo uses your Wajo API key. A generic MCP server is listed for its tools only; it cannot chat.",
                Modifier.padding(top = 6.dp),
                color = PallyColor.TextMuted,
                fontSize = PallyType.RowSub,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun AssistantCard(name: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    PanelCard(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(PallyShape.Card)
            .clickable(role = Role.RadioButton, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(PallyColor.Control)
                    .border(1.dp, PallyColor.Border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (name == PALLY) {
                    PallyLogoImage(size = 28.dp)
                } else {
                    Text(if (name == FO) "Fo" else "MCP", color = PallyColor.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(name, color = PallyColor.Text, fontSize = PallyType.Row)
                Text(detail, Modifier.padding(top = 3.dp), color = PallyColor.TextMuted, fontSize = PallyType.RowSub)
            }
            RadioDot(selected)
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (selected) PallyColor.Knob else PallyColor.BorderStrong, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(PallyColor.Accent))
        }
    }
}

/** Lists the providers that can be added. Each one leads to its own sign-in or key setup. */
@Composable
internal fun AddAssistantScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    onPick: (String) -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit
) {
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose
    ) {
        PageTitle("add an assistant", "Select a supported provider")
        SettingRow(
            icon = Icons.Default.Person,
            title = PALLY,
            subtitle = "official assistant · signs in with your Pally account",
            onClick = { onPick(PALLY) }
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 1.dp, color = PallyColor.Divider)
        SettingRow(
            icon = Icons.Default.Lock,
            title = FO,
            subtitle = "single-assistant profile · uses your Wajo API key",
            onClick = { onPick(FO) }
        )
    }
}

/** Connect page for Pally (browser sign-in) and Fo (API key). Generic MCP servers use [McpSetupScreen]. */
@Composable
internal fun ConnectScreen(
    name: String,
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    secret: String,
    onSecret: (String) -> Unit,
    status: String,
    error: String,
    tools: List<McpTool>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit
) {
    val isPally = name == PALLY
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose
    ) {
        PageTitle(if (isPally) "Pally connection" else "Fo by Wajo", if (isPally) "Sign in once; the app keeps the token encrypted on this phone." else "Connect the documented Fo profile.")
        if (isPally) {
            PanelCard {
                Text(
                    "Public-client OAuth 2.1 with PKCE and state checking. You sign in in your browser and return here when it finishes.",
                    color = PallyColor.TextMuted,
                    fontSize = PallyType.RowSub,
                    lineHeight = 18.sp
                )
            }
        } else {
            PanelCard {
                Text(FO_URL, color = PallyColor.Accent, fontSize = 12.sp)
                Text(
                    "Wajo documents ask_fo and get_fo_reply for this profile. A live account connection still needs testing.",
                    Modifier.padding(top = 6.dp),
                    color = PallyColor.TextMuted,
                    fontSize = PallyType.RowSub,
                    lineHeight = 18.sp
                )
            }
            SectionLabel("Wajo API key")
            PallyField(value = secret, onValueChange = onSecret, placeholder = "wak_…", secret = true, keyboardType = KeyboardType.Password)
            Text(
                "Encrypted on this phone after a successful connection. Never paste it into chat.",
                Modifier.padding(top = 8.dp),
                color = PallyColor.TextFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        PallyPill(
            text = if (busy) "connecting…" else if (isPally) "sign in with Pally" else "connect and inspect tools",
            onClick = onConnect,
            enabled = !busy,
            modifier = Modifier.padding(top = 18.dp)
        )
        TextButton(onClick = onDisconnect, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) {
            Text("disconnect and erase saved credentials", color = PallyColor.TextSoft)
        }
        Text(
            status,
            Modifier.padding(top = 8.dp),
            color = if (status.startsWith("connected")) PallyColor.Accent else PallyColor.TextMuted,
            fontSize = 12.sp
        )
        if (error.isNotBlank()) {
            Text(error, Modifier.padding(top = 4.dp), color = PallyColor.Danger, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (tools.isNotEmpty()) {
            SectionLabel("server-advertised tools · ${tools.size}")
            ToolList(tools)
        }
    }
}

/** Generic MCP server form: name, endpoint, authentication method and trust note, then continue. */
@Composable
internal fun McpSetupScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    serverName: String,
    onServerName: (String) -> Unit,
    endpoint: String,
    onEndpoint: (String) -> Unit,
    method: String,
    onMethod: (String) -> Unit,
    secret: String,
    onSecret: (String) -> Unit,
    error: String,
    onContinue: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit
) {
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = {
            if (error.isNotBlank()) {
                Text(error, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = PallyColor.Danger, fontSize = 12.sp, lineHeight = 17.sp)
            }
            PallyPill(text = if (busy) "connecting…" else "continue", onClick = onContinue, enabled = !busy)
        }
    ) {
        PageTitle("MCP server", "Add a server endpoint from a provider you trust")
        SectionLabel("server name")
        PallyField(value = serverName, onValueChange = onServerName, placeholder = "name this connection")
        SectionLabel("server URL")
        PallyField(value = endpoint, onValueChange = onEndpoint, placeholder = "paste the server URL", keyboardType = KeyboardType.Uri)
        SectionLabel("authentication")
        MethodField(method = method, onMethod = onMethod)
        if (method != AUTH_NONE) {
            SectionLabel("credential")
            PallyField(value = secret, onValueChange = onSecret, placeholder = "server credential", secret = true, keyboardType = KeyboardType.Password)
        }
        Spacer(Modifier.height(18.dp))
        PanelCard {
            Text("Only connect servers you trust", color = PallyColor.Text, fontSize = PallyType.Row)
            Text(
                "Review the provider's access and authentication details before continuing. The credential stays encrypted on this phone.",
                Modifier.padding(top = 6.dp),
                color = PallyColor.TextMuted,
                fontSize = PallyType.RowSub,
                lineHeight = 18.sp
            )
        }
    }
}

private fun methodLabel(method: String): String = when (method) {
    AUTH_HEADER -> "X-API-Key header"
    AUTH_BEARER -> "Bearer token"
    else -> "no authentication"
}

@Composable
private fun MethodField(method: String, onMethod: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = PallyShape.Field
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(shape)
                .background(PallyColor.CardQuiet)
                .border(1.dp, PallyColor.Border, shape)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(methodLabel(method), Modifier.weight(1f), color = PallyColor.Text, fontSize = PallyType.Body)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = PallyColor.TextSoft)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(AUTH_HEADER, AUTH_BEARER, AUTH_NONE).forEach { option ->
                DropdownMenuItem(
                    text = { Text(methodLabel(option), color = PallyColor.Text) },
                    onClick = {
                        onMethod(option)
                        open = false
                    }
                )
            }
        }
    }
}

/** Review step after a generic server connects. Tools are listed for review and never run here. */
@Composable
internal fun ReviewAccessScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    serverName: String,
    tools: List<McpTool>,
    onDone: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit
) {
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = { PallyPill(text = "done", onClick = onDone) }
    ) {
        PageTitle("review access", "Review what this server advertises")
        PanelCard(Modifier.padding(top = 4.dp)) {
            Text(serverName, color = PallyColor.Text, fontSize = PallyType.Row)
            Text("connected · ${tools.size} tools advertised", Modifier.padding(top = 3.dp), color = PallyColor.TextMuted, fontSize = PallyType.RowSub)
        }
        SectionLabel("advertised tools")
        if (tools.isEmpty()) {
            Text("This server advertised no tools.", color = PallyColor.TextMuted, fontSize = PallyType.RowSub)
        } else {
            ToolList(tools)
        }
        Spacer(Modifier.height(16.dp))
        PanelCard {
            Text("Listed for review only", color = PallyColor.Text, fontSize = PallyType.Row)
            Text(
                "This app runs only the documented Pally and Fo chat tools. Other tools are shown here and are never called.",
                Modifier.padding(top = 6.dp),
                color = PallyColor.TextMuted,
                fontSize = PallyType.RowSub,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun ToolList(tools: List<McpTool>) {
    tools.take(40).forEach { tool ->
        PanelCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(tool.name, color = PallyColor.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (tool.description.isNotBlank()) {
                Text(
                    tool.description,
                    Modifier.padding(top = 4.dp),
                    color = PallyColor.TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 3
                )
            }
        }
    }
}

/** Preferences: appearance and conversation settings. Every row either changes something or opens a page. */
@Composable
internal fun PreferencesScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    motionOn: Boolean,
    onMotion: (Boolean) -> Unit,
    reduceMotion: Boolean,
    textSizeLabel: String,
    onTextSize: () -> Unit,
    onAssistant: () -> Unit,
    onVoice: () -> Unit,
    onPrivacy: () -> Unit,
    onHelp: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit
) {
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = { PallyPill(text = "done", onClick = onDone) }
    ) {
        PageTitle("preferences", "Make Pally feel right on your phone")
        SectionLabel("appearance")
        SettingRow(icon = Icons.Default.DarkMode, title = "theme", subtitle = "dark, the only theme in this version")
        SettingRow(
            icon = Icons.Default.Refresh,
            title = "motion",
            subtitle = if (reduceMotion) "system animations are turned off" else "logo states for thinking and searching",
            trailing = { PallyToggle(checked = motionOn, onCheckedChange = onMotion, label = "Logo motion") }
        )
        SettingRow(icon = Icons.Default.TextFields, title = "text size", subtitle = textSizeLabel, onClick = onTextSize)
        HorizontalDivider(Modifier.padding(top = 10.dp), thickness = 1.dp, color = PallyColor.Divider)
        SectionLabel("conversation")
        SettingRow(icon = Icons.Default.Person, title = "assistant", subtitle = "choose a connection", onClick = onAssistant)
        SettingRow(icon = Icons.Default.Mic, title = "voice setup", subtitle = "Gemini Live key for calls", onClick = onVoice)
        SettingRow(icon = Icons.Default.Lock, title = "data & privacy", subtitle = "manage what is saved", onClick = onPrivacy)
        SettingRow(icon = Icons.Default.Help, title = "help", subtitle = "what works and what does not", onClick = onHelp)
    }
}

@Composable
internal fun PrivacyScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    onErase: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit
) {
    var confirm by remember { mutableStateOf(false) }
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = {
            PallyPill(text = "erase conversation history", onClick = { confirm = true })
            Spacer(Modifier.height(10.dp))
            PallyPill(text = "done", onClick = onDone)
        }
    ) {
        PageTitle("data & privacy", "What this phone keeps")
        PanelCard(Modifier.padding(top = 12.dp)) {
            Text("conversations", color = PallyColor.Text, fontSize = PallyType.Row)
            Text(
                "Up to 200 messages per assistant, encrypted with a key in the Android Keystore. Sample messages are never saved.",
                Modifier.padding(top = 6.dp),
                color = PallyColor.TextMuted,
                fontSize = PallyType.RowSub,
                lineHeight = 18.sp
            )
        }
        PanelCard(Modifier.padding(top = 10.dp)) {
            Text("sign-in and keys", color = PallyColor.Text, fontSize = PallyType.Row)
            Text(
                "Pally tokens, the Wajo API key and the Gemini key are encrypted on this phone. Each connection page can erase its own credentials, and voice setup erases the Gemini key.",
                Modifier.padding(top = 6.dp),
                color = PallyColor.TextMuted,
                fontSize = PallyType.RowSub,
                lineHeight = 18.sp
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = PallyColor.Card,
            title = { Text("erase conversation history?", color = PallyColor.Text) },
            text = { Text("This removes the saved messages for every assistant on this phone. It cannot be undone.", color = PallyColor.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onErase()
                }) { Text("erase", color = PallyColor.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("keep", color = PallyColor.Accent) } }
        )
    }
}

@Composable
internal fun HelpScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit
) {
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = { PallyPill(text = "done", onClick = onDone) }
    ) {
        PageTitle("help", "What works and what does not")
        SectionLabel("works now")
        HelpCard("Pally", "Sign in with OAuth, then chat through Pally's message tool. Replies are polled while Pally is still working.")
        HelpCard("Fo by Wajo", "Connect with your Wajo API key. Chat uses ask_fo and get_fo_reply.")
        HelpCard("Gemini Live", "Voice calls with your own Gemini key. Experimental, and not yet tested with a live account.")
        HelpCard("Other MCP servers", "Connect and list their tools. This app does not run them.")
        SectionLabel("not available yet")
        HelpCard("Attachments", "Nothing is uploaded. The options in the add menu stay unavailable.")
        HelpCard("Reactions", "No connected provider has a verified reaction interface, so none are shown.")
        HelpCard("Searching", "The searching state appears only when a server reports that it is searching.")
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    PanelCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, color = PallyColor.Text, fontSize = PallyType.Row)
        Text(body, Modifier.padding(top = 4.dp), color = PallyColor.TextMuted, fontSize = PallyType.RowSub, lineHeight = 18.sp)
    }
}

@Composable
internal fun VoiceSetupScreen(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    saved: Boolean,
    onSave: (String) -> Unit,
    onErase: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onClose: () -> Unit,
    onDone: () -> Unit
) {
    var key by remember { mutableStateOf("") }
    // Format check only: Google issues keys as long opaque strings with no spaces. The key is verified by a real call.
    val trimmed = key.trim()
    val keyLooksValid = trimmed.length >= 20 && trimmed.none { it.isWhitespace() }
    PageFrame(
        assistant = assistant,
        connected = connected,
        busy = busy,
        motion = motion,
        trailingIcon = Icons.Default.Close,
        trailingLabel = "Close",
        onMenu = onMenu,
        onMark = onMark,
        onTrailing = onClose,
        footer = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PallyPill(
                        text = "save on this device",
                        onClick = {
                            if (key.isNotBlank()) {
                                onSave(key.trim())
                                key = ""
                            }
                        },
                        enabled = keyLooksValid
                    )
                }
                TextButton(onClick = {
                    onErase()
                    key = ""
                }) { Text("erase", color = PallyColor.Danger) }
            }
            Spacer(Modifier.height(10.dp))
            PallyPill(text = "done", onClick = onDone)
        }
    ) {
        PageTitle("voice setup", "Gemini Live uses your own key")
        PanelCard(Modifier.padding(top = 12.dp)) {
            Text(
                "Calls send audio straight from this phone to Google with the key saved here. A key on the device can be extracted, so use a test key. For production, issue short-lived tokens from a backend.",
                color = PallyColor.TextMuted,
                fontSize = PallyType.RowSub,
                lineHeight = 18.sp
            )
        }
        SectionLabel("Gemini API key")
        PallyField(value = key, onValueChange = { key = it }, placeholder = "paste the key", secret = true, keyboardType = KeyboardType.Password)
        if (key.isNotBlank() && !keyLooksValid) {
            Text(
                "That does not look like a Gemini API key. Keys are long and contain no spaces.",
                Modifier.padding(top = 8.dp),
                color = PallyColor.Danger,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
        Text(
            if (saved) "key saved encrypted on this device" else "no key saved",
            Modifier.padding(top = 8.dp),
            color = PallyColor.TextFaint,
            fontSize = 11.sp
        )
        Text(
            "The microphone is requested only when a call starts.",
            Modifier.padding(top = 4.dp),
            color = PallyColor.TextFaint,
            fontSize = 11.sp
        )
    }
}
