package com.ichiro.pallyagent

import org.json.JSONArray
import org.json.JSONObject

/** Uses only the Fo tools and arguments actually advertised by Wajo's MCP server. */
object FoAdapter {
    suspend fun send(client: McpClient, tools: List<McpTool>, message: String): String {
        val ask = tools.firstOrNull { it.name == "ask_fo" }
            ?: throw IllegalStateException("The connected MCP profile does not expose ask_fo, so it is not the Fo profile.")
        var call = client.callTool(ask.name, McpClient.messageArguments(ask, message))
        requireSuccessful(call)

        repeat(3) {
            val payload = payload(call)
            if (!isWorking(payload)) return McpClient.toolText(call)
            val pollTool = tools.firstOrNull { it.name == "get_fo_reply" }
                ?: return "Fo accepted the request and is still working, but this server did not expose get_fo_reply."
            val args = pollingArguments(pollTool, payload)
                ?: return "Fo accepted the request and is still working. The reply handle could not be mapped safely to the poll tool's schema."
            call = client.callTool(pollTool.name, args)
            requireSuccessful(call)
        }

        val finalPayload = payload(call)
        return if (isWorking(finalPayload)) "Fo is still working. Try again in a moment to collect its reply."
        else McpClient.toolText(call)
    }

    private fun requireSuccessful(call: McpCallResult) {
        if (call.isError) throw IllegalStateException(McpClient.toolText(call))
    }

    internal fun isWorking(payload: JSONObject?): Boolean {
        if (payload == null) return false
        val status = payload.optString("status").ifBlank { payload.optString("state") }
        return status.equals("working", true) || status.equals("pending", true)
    }

    private fun payload(call: McpCallResult): JSONObject? {
        call.result.optJSONObject("structuredContent")?.let { return it }
        val content = call.result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val text = content.optJSONObject(i)?.optString("text").orEmpty()
            if (text.isBlank()) continue
            try { return JSONObject(text) } catch (_: Exception) { }
        }
        return null
    }

    /** Map an issued Fo handle only to a schema-declared ID field with the same semantic name. */
    internal fun pollingArguments(pollTool: McpTool, payload: JSONObject?): JSONObject? {
        if (payload == null) return null
        val properties = pollTool.inputSchema.optJSONObject("properties") ?: JSONObject()
        val required = pollTool.inputSchema.optJSONArray("required") ?: JSONArray()
        val output = JSONObject()
        val issuedHandleNames = listOf("request_id", "requestId", "task_id", "taskId", "handle", "job_id", "id", "conversation_id")

        for (name in properties.keys()) {
            if (!name.contains("id", ignoreCase = true) && !name.contains("handle", ignoreCase = true)) continue
            val direct = readString(payload, name)
            val genericHandle = if (normalize(name) == "id" || normalize(name) == "handle") {
                issuedHandleNames.firstNotNullOfOrNull { readString(payload, it) }
            } else null
            (direct ?: genericHandle)?.takeIf { it.isNotBlank() }?.let { output.put(name, it) }
        }

        for (i in 0 until required.length()) {
            if (!output.has(required.optString(i))) return null
        }
        return output
    }

    private fun normalize(value: String) = value.filter(Char::isLetterOrDigit).lowercase()

    private fun readString(value: JSONObject, key: String): String? {
        val matches = linkedSetOf<String>()
        fun walk(node: JSONObject) {
            val keys = node.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val child = node.opt(name)
                if (normalize(name) == normalize(key) && child is String && child.isNotBlank()) matches += child
                if (child is JSONObject) walk(child)
                if (child is JSONArray) {
                    for (i in 0 until child.length()) child.optJSONObject(i)?.let(::walk)
                }
            }
        }
        walk(value)
        return matches.singleOrNull()
    }
}