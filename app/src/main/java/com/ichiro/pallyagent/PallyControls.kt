package com.ichiro.pallyagent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Faint horizontal lines behind every screen, drawn once because nothing here reads state. */
@Composable
internal fun Scanlines(modifier: Modifier) {
    Canvas(modifier) {
        val brush = Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                Color.White.copy(alpha = .020f),
                Color.White.copy(alpha = .038f),
                Color.White.copy(alpha = .020f),
                Color.Transparent
            )
        )
        val spacing = 5.dp.toPx()
        val stroke = .55.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(brush, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            y += spacing
        }
    }
}

/** Round icon button with the soft top-left highlight from the approved screens. */
@Composable
internal fun CircleControl(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    enabled: Boolean = true
) {
    Box(
        Modifier
            .size(size)
            .semantics { contentDescription = label }
            .clip(CircleShape)
            .background(PallyColor.Control)
            .border(1.dp, PallyColor.Border, CircleShape)
            .drawBehind { drawRingHighlight(alpha = .24f) }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) PallyColor.Text else PallyColor.TextFaint,
            modifier = Modifier.size(if (size <= 48.dp) 21.dp else 24.dp)
        )
    }
}

/** Non-interactive icon in the same circle style, for rows that are tapped as a whole. */
@Composable
internal fun IconBadge(icon: ImageVector, description: String?, size: Dp = 44.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(PallyColor.Control)
            .border(1.dp, PallyColor.Border, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = PallyColor.Text, modifier = Modifier.size(20.dp))
    }
}

/** The assistant mark in the header. It pulses and orbits only while [active] and motion is allowed. */
@Composable
internal fun PallyMark(
    name: String,
    active: Boolean,
    motion: Boolean,
    onClick: () -> Unit,
    size: Dp = 62.dp,
    connected: Boolean? = null
) {
    val animate = active && motion
    Box(
        Modifier
            .size(size)
            .drawBehind { drawMarkGlow() }
            .semantics { contentDescription = "$name, choose assistant" }
            .clip(CircleShape)
            .background(PallyColor.Ink)
            .border(1.dp, PallyColor.Border, CircleShape)
            .drawBehind { drawRingHighlight(alpha = .30f) }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (animate) OrbitArc()
        if (name == PALLY) {
            // The Pally icon is a black tile whose dots sit in the middle ~60%, so draw it close to the full ring.
            PallyPulseLogo(size = size * .9f, animate = animate)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (name == FO) "Fo" else "MCP", color = PallyColor.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (name == FO) Text("by Wajo", color = PallyColor.TextMuted, fontSize = 8.sp)
            }
        }
        if (connected != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (connected) PallyColor.Accent else PallyColor.TextFaint)
            )
        }
    }
}

@Composable
internal fun PallyPulseLogo(size: Dp, animate: Boolean) {
    if (animate) PulsingLogo(size) else PallyLogoImage(size)
}

@Composable
private fun PulsingLogo(size: Dp) {
    val transition = rememberInfiniteTransition(label = "pally-pulse")
    val pulse by transition.animateFloat(
        initialValue = .97f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(animation = tween(PallyMotion.PulseMs), repeatMode = RepeatMode.Reverse),
        label = "pulse"
    )
    // Read in the draw phase, so the pulse does not recompose the logo every frame.
    PallyLogoImage(
        size = size,
        modifier = Modifier.graphicsLayer {
            val scale = pulse
            scaleX = scale
            scaleY = scale
        }
    )
}

/** Thin bright arc that travels around the mark while the assistant is working. */
@Composable
private fun OrbitArc() {
    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PallyMotion.OrbitMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit-angle"
    )
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 1.6.dp.toPx()
        val inset = stroke / 2f
        drawArc(
            color = PallyColor.Accent.copy(alpha = .9f),
            startAngle = angle - 90f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
internal fun PallyLogoImage(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.pally_logo),
        contentDescription = null,
        modifier = modifier.size(size).clip(CircleShape)
    )
}

private fun DrawScope.drawRingHighlight(alpha: Float) {
    val stroke = 1.2.dp.toPx()
    val inset = stroke / 2f
    drawArc(
        color = Color.White.copy(alpha = alpha),
        startAngle = 200f,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(this.size.width - stroke, this.size.height - stroke),
        style = Stroke(width = stroke, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawMarkGlow() {
    val radius = size.minDimension * .78f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = .12f), Color.Transparent),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** Header shared by every screen: menu, assistant mark, and a trailing action (call or close). */
@Composable
internal fun AppHeader(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    trailingIcon: ImageVector,
    trailingLabel: String,
    onTrailing: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PallySpace.Page, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircleControl(Icons.Default.Menu, "Open your work", onMenu, size = 52.dp)
        PallyMark(assistant, active = busy, motion = motion, onClick = onMark, size = 66.dp, connected = connected)
        CircleControl(trailingIcon, trailingLabel, onTrailing, size = 52.dp)
    }
}

/** Header plus a scrolling body and an optional fixed footer, used by the setup screens. */
@Composable
internal fun PageFrame(
    assistant: String,
    connected: Boolean,
    busy: Boolean,
    motion: Boolean,
    trailingIcon: ImageVector,
    trailingLabel: String,
    onMenu: () -> Unit,
    onMark: () -> Unit,
    onTrailing: () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        AppHeader(
            assistant = assistant,
            connected = connected,
            busy = busy,
            motion = motion,
            onMenu = onMenu,
            onMark = onMark,
            trailingIcon = trailingIcon,
            trailingLabel = trailingLabel,
            onTrailing = onTrailing
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PallySpace.Page),
            content = content
        )
        if (footer != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PallySpace.Page, vertical = 14.dp),
                content = footer
            )
        }
    }
}

@Composable
internal fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp)) {
        Text(title, color = PallyColor.Text, fontSize = PallyType.Title, lineHeight = 32.sp)
        if (subtitle != null) {
            Text(subtitle, Modifier.padding(top = 4.dp), color = PallyColor.TextSoft, fontSize = PallyType.Subtitle)
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp),
        color = PallyColor.TextSoft,
        fontSize = PallyType.Label
    )
}

/** A row with an icon badge, title and subtitle. Pass [onClick] to make it tappable, or [trailing] for a control. */
@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val base = Modifier.fillMaxWidth()
    val rowModifier = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
    Row(rowModifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, title)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, color = PallyColor.Text, fontSize = PallyType.Row)
            Text(
                subtitle,
                Modifier.padding(top = 3.dp),
                color = PallyColor.TextMuted,
                fontSize = PallyType.RowSub,
                lineHeight = 16.sp
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = PallyColor.TextMuted,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
internal fun PallyPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(PallyShape.Pill)
            .background(if (enabled) PallyColor.ButtonFill else PallyColor.CardQuiet)
            .border(1.dp, if (enabled) PallyColor.BorderStrong else PallyColor.Border, PallyShape.Pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) PallyColor.Text else PallyColor.TextFaint, fontSize = PallyType.Body)
    }
}

@Composable
internal fun PallyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val shape = PallyShape.Field
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(PallyColor.CardQuiet)
            .border(1.dp, PallyColor.Border, shape)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        textStyle = TextStyle(color = PallyColor.Text, fontSize = PallyType.Body, lineHeight = 22.sp),
        cursorBrush = SolidColor(PallyColor.Accent),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = PallyColor.Placeholder, fontSize = PallyType.Body)
                inner()
            }
        }
    )
}

/** Quiet rounded panel used for notes, forms and lists. Callers set the width through [modifier]. */
@Composable
internal fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(PallyShape.Card)
            .background(PallyColor.CardQuiet)
            .border(1.dp, PallyColor.Border, PallyShape.Card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        content = content
    )
}

@Composable
internal fun PallyToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.semantics { contentDescription = label },
        colors = SwitchDefaults.colors(
            checkedThumbColor = PallyColor.Knob,
            checkedTrackColor = PallyColor.TrackOn,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = PallyColor.TextSoft,
            uncheckedTrackColor = PallyColor.TrackOff,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

@Composable
internal fun SectionDivider() {
    androidx.compose.material3.HorizontalDivider(Modifier.fillMaxWidth(), thickness = 1.dp, color = PallyColor.Divider)
}
