package com.ichiro.pallyagent

import org.json.JSONArray
import org.json.JSONObject

/** Uses only the Fo tools and argument fields actually advertised by Wajo's MCP server. */
object FoAdapter {
    suspend fun send(client: McpClient, tools: List<McpTool>, message: String): String {
        val ask = tools.firstOrNull { it.name == "ask_fo" }
            ?: throw IllegalStateException("The connected MCP profile does not expose ask_fo, so it is not the Fo profile.")
        var call = client.callTool(ask.name, McpClient.messageArguments(ask, message))
        repeat(3) {
            val payload = payload(call)
            if (!isWorking(payload)) return McpClient.toolText(call)
            val pollTool = tools.firstOrNull { it.name == "get_fo_reply" }
                ?: return "Fo accepted the request and is still working, but this server did not expose get_fo_reply."
            val args = pollingArguments(pollTool, payload)
                ?: return "Fo accepted the request and is still working. The server's reply handle could not be mapped safely."
            call = client.callTool(pollTool.name, args)
        }
        val finalPayload = payload(call)
        if (isWorking(finalPayload)) return "Fo is still working. Try again in a moment to collect its reply."
        return McpClient.toolText(call)
    }

    private fun isWorking(payload: JSONObject?): Boolean {
        if (payload == null) return false
        val status = payload.optString("status").ifBlank { payload.optString("state") }
        return status.equals("working", true) || status.equals("pending", true)
    }

    private fun payload(call: McpCallResult): JSONObject? {
        call.result.optJSONObject("structuredContent")?.let { return it }
        val content = call.result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isBlank()) continue
            try { return JSONObject(text) } catch (_: Exception) { }
        }
        return null
    }

    private fun pollingArguments(tool: McpTool, payload: JSONObject?): JSONObject? {
        if (payload == null) return null
        val properties = tool.inputSchema.optJSONObject("properties") ?: JSONObject()
        val required = tool.inputSchema.optJSONArray("required") ?: JSONArray()
        val output = JSONObject()
        val idNames = listOf("request_id", "requestId", "task_id", "taskId", "handle", "job_id", "id", "conversation_id")
        for (name in properties.keys()) {
            if (!name.contains("id", true) && !name.contains("handle", true)) continue
            val candidate = idNames.firstNotNullOfOrNull { readString(payload, it) }
            if (!candidate.isNullOrBlank()) output.put(name, candidate)
        }
        for (i in 0 until required.length()) {
            if (!output.has(required.optString(i))) return null
        }
        return output
    }

    private fun readString(value: JSONObject, key: String): String? {
        val direct = value.opt(key)
        if (direct is String && direct.isNotBlank()) return direct
        for (nestedName in value.keys()) {
            val nested = value.optJSONObject(nestedName) ?: continue
            readString(nested, key)?.let { return it }
        }
        return null
    }
}
