package com.ichiro.pallyagent

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Implements only the two conversational tools Pally documents. */
object PallyAdapter {
    suspend fun send(client: McpClient, tools: List<McpTool>, message: String): String {
        val sendTool = tools.firstOrNull { it.name == "message_pally" }
            ?: throw IllegalStateException("Pally did not advertise message_pally.")
        var call = client.callTool(sendTool.name, McpClient.messageArguments(sendTool, message))
        ensureSuccess(call)
        repeat(10) {
            val payload = payload(call)
            if (!isPending(payload)) return McpClient.toolText(call)
            val pollTool = tools.firstOrNull { it.name == "check_pally" }
                ?: return "Pally accepted the message and is still working, but did not advertise check_pally."
            val arguments = pollingArguments(pollTool, payload)
                ?: return "Pally accepted the message and is still working. Its reply handle could not be mapped safely to check_pally's schema."
            delay(900)
            call = client.callTool(pollTool.name, arguments)
            ensureSuccess(call)
        }
        return if (isPending(payload(call))) "Pally is still working. Try again in a moment to collect the reply."
        else McpClient.toolText(call)
    }

    internal fun isPending(payload: JSONObject?): Boolean {
        if (payload == null) return false
        val status = payload.optString("status").ifBlank { payload.optString("state") }.lowercase()
        return status in setOf("pending", "working", "queued", "processing", "accepted")
    }

    /** Maps only issued handle values to same-named string fields accepted by check_pally. */
    internal fun pollingArguments(pollTool: McpTool, payload: JSONObject?): JSONObject? {
        if (payload == null) return null
        val properties = pollTool.inputSchema.optJSONObject("properties") ?: JSONObject()
        val required = pollTool.inputSchema.optJSONArray("required") ?: JSONArray()
        val result = JSONObject()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val field = keys.next()
            if (!field.contains("id", true) && !field.contains("handle", true)) continue
            val schema = properties.optJSONObject(field) ?: continue
            if (schema.optString("type") != "string") continue
            val value = readUniqueString(payload, field) ?: if (normalize(field) == "id" || normalize(field) == "handle") genericHandle(payload) else null
            if (!value.isNullOrBlank()) result.put(field, value)
        }
        for (index in 0 until required.length()) {
            val field = required.optString(index)
            if (!result.has(field)) return null
        }
        return result
    }

    private fun ensureSuccess(call: McpCallResult) {
        if (call.isError) throw IllegalStateException(McpClient.toolText(call))
    }

    private fun payload(call: McpCallResult): JSONObject? {
        call.result.optJSONObject("structuredContent")?.let { return it }
        val content = call.result.optJSONArray("content") ?: return null
        for (index in 0 until content.length()) {
            val text = content.optJSONObject(index)?.optString("text").orEmpty()
            if (text.isBlank()) continue
            try { return JSONObject(text) } catch (_: Exception) { }
        }
        return null
    }

    private fun normalize(value: String): String = value.filter(Char::isLetterOrDigit).lowercase()

    private fun genericHandle(payload: JSONObject): String? {
        val names = listOf("request_id", "requestId", "request_handle", "requestHandle", "task_id", "taskId", "job_id", "jobId", "reply_handle", "replyHandle", "handle", "id")
        val values = names.mapNotNull { readUniqueString(payload, it) }.distinct()
        return values.singleOrNull()
    }

    private fun readUniqueString(value: JSONObject, expectedName: String): String? {
        val matches = linkedSetOf<String>()
        fun visit(node: JSONObject) {
            val iterator = node.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                val child = node.opt(key)
                if (normalize(key) == normalize(expectedName) && child is String && child.isNotBlank()) matches += child
                when (child) {
                    is JSONObject -> visit(child)
                    is JSONArray -> for (index in 0 until child.length()) child.optJSONObject(index)?.let(::visit)
                }
            }
        }
        visit(value)
        return matches.singleOrNull()
    }
}
