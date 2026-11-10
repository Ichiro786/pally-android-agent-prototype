package com.ichiro.pallyagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A bottom panel over a scrim. Tapping the scrim dismisses it. */
@Composable
internal fun BottomPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(PallyColor.Scrim).clickable(onClick = onDismiss))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(PallyShape.Sheet)
                .background(PallyColor.Card)
                .border(1.dp, PallyColor.Border, PallyShape.Sheet)
                .padding(start = PallySpace.Page, end = PallySpace.Page, top = 10.dp, bottom = 18.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(PallyColor.BorderStrong)
            )
            content()
        }
    }
}

/**
 * The "add to message" panel from the approved attachments screen. None of these options can
 * upload today, so each row says so instead of pretending to attach.
 */
@Composable
internal fun PallyAttachmentSheet(onDismiss: () -> Unit) {
    BottomPanel(onDismiss = onDismiss) {
        Text(
            "add to message",
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
            color = PallyColor.Text,
            fontSize = 18.sp
        )
        AttachmentRow(Icons.Default.CameraAlt, "camera", "take a photo")
        HorizontalDivider(thickness = 1.dp, color = PallyColor.Divider)
        AttachmentRow(Icons.Default.Image, "photos", "choose an image")
        HorizontalDivider(thickness = 1.dp, color = PallyColor.Divider)
        AttachmentRow(Icons.Default.AttachFile, "files", "attach a document")
        HorizontalDivider(thickness = 1.dp, color = PallyColor.Divider)
        AttachmentRow(Icons.Default.LocationOn, "location", "share a place")
        Text(
            "Nothing is uploaded. Neither Pally nor Fo has an attachment interface verified in this app, so these options stay unavailable.",
            Modifier.fillMaxWidth().padding(top = 14.dp),
            color = PallyColor.TextFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun AttachmentRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon, title, size = 40.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = PallyColor.TextMuted, fontSize = PallyType.Row)
            Text(subtitle, Modifier.padding(top = 2.dp), color = PallyColor.TextFaint, fontSize = PallyType.RowSub)
        }
        Text("unavailable", color = PallyColor.TextFaint, fontSize = 11.sp)
    }
}

@Composable
internal fun PallyEmojiSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    BottomPanel(onDismiss = onDismiss) {
        Text(
            "choose emoji",
            Modifier.fillMaxWidth().padding(top = 14.dp),
            color = PallyColor.Text,
            fontSize = 18.sp
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("😊", "👍", "❤️", "✨", "🙏").forEach { emoji ->
                Box(
                    Modifier.size(52.dp).clip(CircleShape).clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(emoji, fontSize = 26.sp)
                }
            }
        }
    }
}

/**
 * Work drawer. "In progress" lists only requests that are actually running. The drawer has no
 * search, because nothing is searched yet.
 */
@Composable
internal fun PallyWorkDrawer(
    items: List<WorkItem>,
    onClose: () -> Unit,
    onAssistants: () -> Unit,
    onSettings: () -> Unit
) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(.78f)
                .background(PallyColor.Panel)
                .padding(horizontal = PallySpace.Page, vertical = 22.dp)
        ) {
            Text(
                "our work",
                Modifier.padding(top = 12.dp),
                color = PallyColor.Text,
                fontSize = 28.sp,
                lineHeight = 34.sp
            )
            HorizontalDivider(Modifier.padding(top = 18.dp), thickness = 1.dp, color = PallyColor.Divider)
            SectionLabel("in progress")
            if (items.isEmpty()) {
                Text("no active work", color = PallyColor.TextMuted, fontSize = PallyType.RowSub)
            } else {
                items.forEach { item -> WorkCard(item) }
            }
            SectionLabel("connect")
            SettingRow(
                icon = Icons.Default.Link,
                title = "assistants & tools",
                subtitle = "manage available connections",
                onClick = onAssistants
            )
            Spacer(Modifier.weight(1f))
            HorizontalDivider(thickness = 1.dp, color = PallyColor.Divider)
            SpaceRow(onClick = onSettings)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(PallyColor.Scrim)
                .clickable(onClick = onClose)
        )
    }
}

@Composable
private fun WorkCard(item: WorkItem) {
    PanelCard(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(PallyColor.Control)
                    .border(1.dp, PallyColor.Border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = PallyColor.Accent,
                    strokeWidth = 2.dp
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(item.title, color = PallyColor.Text, fontSize = PallyType.Row)
                Text(item.detail, Modifier.padding(top = 3.dp), color = PallyColor.TextMuted, fontSize = PallyType.RowSub)
            }
        }
    }
}

/** Footer row that opens preferences. The round "p" mark follows the approved drawer. */
@Composable
private fun SpaceRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(PallyColor.Text),
            contentAlignment = Alignment.Center
        ) {
            Text("p", color = PallyColor.Ink, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text("your space", color = PallyColor.Text, fontSize = PallyType.Row)
            Text("settings and profile", Modifier.padding(top = 3.dp), color = PallyColor.TextMuted, fontSize = PallyType.RowSub)
        }
        Icon(Icons.Default.Settings, contentDescription = null, tint = PallyColor.TextMuted, modifier = Modifier.size(20.dp))
    }
}
