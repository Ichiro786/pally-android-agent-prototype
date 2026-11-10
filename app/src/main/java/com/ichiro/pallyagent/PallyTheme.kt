package com.ichiro.pallyagent

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colour tokens sampled from the approved illustrations: warm near-black, soft off-white text, sage accent. */
internal object PallyColor {
    val Ink = Color(0xFF0C0C0A)
    val Panel = Color(0xFF090908)
    val Scrim = Color(0xB3050505)
    val Card = Color(0xFF1E1D1B)
    val CardQuiet = Color(0xFF181715)
    val Control = Color(0xFF1B1A18)
    val Bubble = Color(0xFF1E1D1B)
    val BubbleUser = Color(0xFF141413)
    val ButtonFill = Color(0xFF2A2925)
    val Border = Color(0xFF302F2C)
    val BorderStrong = Color(0xFF504B47)
    val Divider = Color(0xFF302F2B)
    val Quote = Color(0xFF383735)
    val Text = Color(0xFFFFFEFD)
    val TextSoft = Color(0xFFB9B5B2)
    val TextMuted = Color(0xFFAAA9A4)
    val TextFaint = Color(0xFF7F7D79)
    val Placeholder = Color(0xFF949391)
    val Accent = Color(0xFFBECFC5)
    val TrackOn = Color(0xFF505B53)
    val TrackOff = Color(0xFF33322E)
    val Knob = Color(0xFFEAE9E4)
    val Danger = Color(0xFFFFB4A8)
}

/** Spacing tokens. The approved screens use a 22 dp page margin. */
internal object PallySpace {
    val Page = 22.dp
    val RowGap = 12.dp
}

internal object PallyShape {
    val Card = RoundedCornerShape(22.dp)
    val Field = RoundedCornerShape(20.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Circle = CircleShape
}

/** Type scale. Titles are regular weight, lowercase where the illustrations are lowercase. */
internal object PallyType {
    val Title = 26.sp
    val Subtitle = 13.sp
    val Label = 12.sp
    val Row = 15.sp
    val RowSub = 12.sp
    val Body = 16.sp
    val Small = 11.sp
}

internal object PallyMotion {
    const val FadeMs = 180
    const val PulseMs = 1050
    const val OrbitMs = 3200
}

@Composable
internal fun PallyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PallyColor.Accent,
            onPrimary = PallyColor.Ink,
            background = PallyColor.Ink,
            onBackground = PallyColor.Text,
            surface = PallyColor.Card,
            onSurface = PallyColor.Text,
            outline = PallyColor.BorderStrong
        ),
        content = content
    )
}
