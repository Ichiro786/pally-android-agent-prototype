package com.ichiro.pallyagent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal const val PALLY = "Pally"
internal const val FO = "Fo by Wajo"
internal const val CUSTOM = "Custom MCP tools"
internal const val FO_URL = "https://mcp.api.wajo.ai/fo"
internal const val FO_KEY = "wajo_mcp_api_key"
internal const val GEMINI_KEY = "gemini_live_key"

/** Every screen the app can show. The activity keeps a back stack of these. */
internal enum class Page {
    CHAT, ASSISTANTS, ADD_ASSISTANT, CONNECT, MCP_SETUP, REVIEW_ACCESS,
    VOICE, VOICE_SETUP, PREFERENCES, PRIVACY, HELP
}

/** Real phases of a reply request. They come from the adapters, not from a script. */
enum class ReplyStage { NONE, SENT, WORKING, SEARCHING }

internal data class Line(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val assistant: Boolean,
    val local: Boolean = false,
    val sample: Boolean = false,
    val quote: String? = null,
    val time: String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
)

/** One entry in the work drawer. Only real activity is listed; nothing is invented. */
internal data class WorkItem(val title: String, val detail: String)

internal fun stageLabel(stage: ReplyStage): String = when (stage) {
    ReplyStage.NONE -> "sending"
    ReplyStage.SENT -> "sent · waiting for the reply"
    ReplyStage.WORKING -> "waiting for the reply"
    ReplyStage.SEARCHING -> "searching"
}
