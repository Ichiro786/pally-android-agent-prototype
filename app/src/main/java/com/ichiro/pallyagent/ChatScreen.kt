package com.ichiro.pallyagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The main conversation screen: header, thread, real thinking state, reply bar, note and composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PallyChatScreen(
    assistant: String,
    connected: Boolean,
    lines: List<Line>,
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    stage: ReplyStage,
    motion: Boolean,
    textScale: Float,
    note: String,
    onDismissNote: () -> Unit,
    quote: String?,
    onClearQuote: () -> Unit,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onVoice: () -> Unit,
    onAdd: () -> Unit,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
    onReply: (Line) -> Unit
) {
    val list = rememberLazyListState()
    val itemCount = lines.size + if (busy) 1 else 0
    val imeVisible = WindowInsets.isImeVisible
    // Keep the newest message in view when messages arrive and when the keyboard opens over the thread.
    LaunchedEffect(itemCount) {
        if (itemCount > 0) list.animateScrollToItem(itemCount - 1)
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible && itemCount > 0) list.animateScrollToItem(itemCount - 1)
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            assistant = assistant,
            connected = connected,
            busy = busy,
            motion = motion,
            onMenu = onMenu,
            onMark = onMark,
            trailingIcon = Icons.Default.Call,
            trailingLabel = "Open voice",
            onTrailing = onVoice
        )
        DayDivider("today")
        if (lines.isEmpty() && !busy) {
            EmptyThread(Modifier.weight(1f).fillMaxWidth())
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = PallySpace.Page, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (lines.any { it.sample }) {
                    item(key = "sample-notice") { SampleNotice() }
                }
                itemsIndexed(lines, key = { _, line -> line.id }) { index, line ->
                    val previous = lines.getOrNull(index - 1)
                    val showName = line.assistant && (previous == null || !previous.assistant)
                    MessageRow(line = line, name = assistant, showName = showName, textScale = textScale, onReply = onReply)
                }
                if (busy) {
                    item(key = "thinking") { ThinkingCard(assistant = assistant, stage = stage, motion = motion) }
                }
            }
        }
        if (note.isNotBlank()) NoteBar(text = note, onDismiss = onDismissNote)
        if (quote != null) ReplyBar(quote = quote, onClear = onClearQuote)
        Composer(
            assistant = assistant,
            draft = draft,
            onDraft = onDraft,
            busy = busy,
            onAdd = onAdd,
            onEmoji = onEmoji,
            onSend = onSend,
            onVoice = onVoice
        )
    }
}

@Composable
private fun DayDivider(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = PallyColor.Divider)
        Text(text, Modifier.padding(horizontal = 14.dp), color = PallyColor.TextMuted, fontSize = PallyType.Label)
        HorizontalDivider(Modifier.weight(1f), thickness = 1.dp, color = PallyColor.Divider)
    }
}

@Composable
private fun EmptyThread(modifier: Modifier) {
    Box(modifier.padding(horizontal = 35.dp), contentAlignment = Alignment.Center) {
        Text(
            "your conversation starts here",
            Modifier.offset(y = (-35).dp),
            color = PallyColor.Text,
            fontSize = 26.sp,
            lineHeight = 33.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SampleNotice() {
    Text(
        "sample preview · not connected",
        Modifier.fillMaxWidth(),
        color = PallyColor.TextFaint,
        fontSize = 10.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun MessageRow(line: Line, name: String, showName: Boolean, textScale: Float, onReply: (Line) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (line.assistant) Alignment.Start else Alignment.End
    ) {
        if (showName) NameTag(name)
        MessageBubble(line = line, textScale = textScale, onReply = onReply)
    }
}

@Composable
private fun NameTag(name: String) {
    Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (name == PALLY) PallyLogoImage(size = 18.dp)
        Text(name, Modifier.padding(start = if (name == PALLY) 8.dp else 0.dp), color = PallyColor.TextSoft, fontSize = PallyType.Label)
    }
}

@Composable
private fun MessageBubble(line: Line, textScale: Float, onReply: (Line) -> Unit) {
    val maxWidth = (LocalConfiguration.current.screenWidthDp * .8f).dp
    val shape = if (line.assistant) {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp)
    } else {
        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
    }
    Column(
        Modifier
            .widthIn(max = maxWidth)
            .clip(shape)
            .background(if (line.assistant) PallyColor.Bubble else PallyColor.BubbleUser)
            .border(1.dp, PallyColor.Border, shape)
            .pointerInput(line.id) { detectTapGestures(onLongPress = { onReply(line) }) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        line.quote?.let { QuoteBlock(it) }
        Text(
            line.text,
            Modifier.padding(top = if (line.quote == null) 0.dp else 8.dp),
            color = PallyColor.Text,
            fontSize = (16f * textScale).sp,
            lineHeight = (22f * textScale).sp
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (line.local) Text("local · not sent  ", color = PallyColor.TextFaint, fontSize = 10.sp)
            Text(line.time, color = PallyColor.TextFaint, fontSize = PallyType.Small)
        }
    }
}

@Composable
private fun QuoteBlock(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PallyColor.Quote)
            .padding(9.dp),
        color = PallyColor.TextSoft,
        fontSize = PallyType.Small,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Shown while a request is in flight. The stage comes from the adapters: "sent" once the request
 * left the app, "searching" only when the server reports that status. Nothing here is scripted.
 */
@Composable
private fun ThinkingCard(assistant: String, stage: ReplyStage, motion: Boolean) {
    val searching = stage == ReplyStage.SEARCHING
    PanelCard(Modifier.fillMaxWidth(.86f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PallyPulseLogo(size = 34.dp, animate = motion)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    if (searching) "Searching" else "$assistant is thinking",
                    color = PallyColor.Text,
                    fontSize = PallyType.Row
                )
                Text(
                    when (stage) {
                        ReplyStage.SEARCHING -> "finding relevant sources"
                        ReplyStage.WORKING -> "organizing the steps"
                        else -> "sending your message"
                    },
                    Modifier.padding(top = 3.dp),
                    color = PallyColor.TextMuted,
                    fontSize = PallyType.RowSub
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = PallyColor.Divider)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            StepDot(label = "sent", active = stage != ReplyStage.NONE)
            StepDot(label = if (searching) "searching" else "waiting", active = stage == ReplyStage.WORKING || searching)
        }
    }
}

@Composable
private fun StepDot(label: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) PallyColor.Accent else PallyColor.TextFaint.copy(alpha = .5f))
        )
        Text(
            label,
            Modifier.padding(top = 6.dp),
            color = if (active) PallyColor.TextSoft else PallyColor.TextFaint,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun NoteBar(text: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PallySpace.Page, vertical = 4.dp)
            .clip(shape)
            .background(PallyColor.CardQuiet)
            .border(1.dp, PallyColor.Border, shape)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, Modifier.weight(1f), color = PallyColor.TextMuted, fontSize = 12.sp, lineHeight = 16.sp)
        TextButton(onClick = onDismiss) { Text("ok", color = PallyColor.Accent) }
    }
}

@Composable
private fun ReplyBar(quote: String, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PallySpace.Page, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            quote,
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(PallyColor.Quote)
                .padding(9.dp),
            color = PallyColor.TextSoft,
            fontSize = PallyType.Small,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = "Cancel reply", tint = PallyColor.TextSoft)
        }
    }
}

@Composable
private fun Composer(
    assistant: String,
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    onAdd: () -> Unit,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit
) {
    val hasText = draft.isNotBlank()
    val pill = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleControl(icon = Icons.Default.Add, label = "Add attachment", onClick = onAdd)
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clip(pill)
                .background(PallyColor.CardQuiet)
                .border(1.dp, PallyColor.Border, pill)
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
                textStyle = TextStyle(color = PallyColor.Text, fontSize = PallyType.Body, lineHeight = 21.sp),
                cursorBrush = SolidColor(PallyColor.Accent),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (hasText && !busy) onSend() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Text("message $assistant", color = PallyColor.Placeholder, fontSize = PallyType.Body)
                        inner()
                    }
                }
            )
            IconButton(onClick = onEmoji) {
                Icon(Icons.Default.EmojiEmotions, contentDescription = "Choose emoji", tint = PallyColor.TextSoft)
            }
        }
        Spacer(Modifier.width(10.dp))
        CircleControl(
            icon = if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
            label = if (hasText) "Send message" else "Open voice",
            onClick = if (hasText) onSend else onVoice,
            enabled = !hasText || !busy
        )
    }
}
