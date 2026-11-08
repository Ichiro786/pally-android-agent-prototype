package com.ichiro.pallyagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** A deliberately small Streamable HTTP client for HTTPS MCP endpoints. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

data class McpCallResult(val result: JSONObject, val isError: Boolean)

class McpClient(
    private val endpoint: String,
    private val credential: String,
    private val credentialHeader: String = "X-API-Key"
) {
    private val ids = AtomicInteger(0)
    private var protocolVersion = "2025-11-25"
    private var sessionId: String? = null

    suspend fun connectAndListTools(): List<McpTool> = withContext(Dispatchers.IO) {
        val url = URL(endpoint)
        require(url.protocol.equals("https", ignoreCase = true)) { "Use an HTTPS MCP endpoint." }
        require(url.userInfo == null && url.host.isNotBlank()) { "Enter a valid HTTPS MCP endpoint." }

        val initParams = JSONObject()
            .put("protocolVersion", protocolVersion)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Pally Android").put("version", "0.3.0"))
        val init = request("initialize", initParams, initializing = true)
        val initResult = init.optJSONObject("result") ?: throw protocolError(init)
        protocolVersion = initResult.optString("protocolVersion").ifBlank { protocolVersion }
        request("notifications/initialized", JSONObject(), notification = true)
        val listed = request("tools/list", JSONObject())
        val result = listed.optJSONObject("result") ?: throw protocolError(listed)
        val tools = result.optJSONArray("tools") ?: JSONArray()
        buildList {
            for (i in 0 until tools.length()) {
                val item = tools.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isNotEmpty()) add(McpTool(name, item.optString("description"), item.optJSONObject("inputSchema") ?: JSONObject()))
            }
        }
    }

    suspend fun callTool(name: String, arguments: JSONObject): McpCallResult = withContext(Dispatchers.IO) {
        require(name.isNotBlank()) { "Choose a tool reported by this server." }
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val response = request("tools/call", params)
        val result = response.optJSONObject("result") ?: throw protocolError(response)
        McpCallResult(result, result.optBoolean("isError", false))
    }

    private suspend fun request(
        method: String,
        params: JSONObject,
        initializing: Boolean = false,
        notification: Boolean = false
    ): JSONObject = withContext(Dispatchers.IO) {
        val requestId = if (notification) null else ids.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
        if (requestId != null) body.put("id", requestId)
        val conn = URL(endpoint).openConnection() as? HttpURLConnection
            ?: throw IllegalStateException("Could not open MCP endpoint.")
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = if (method == "tools/call") 55_000 else 30_000
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            if (credential.isNotBlank()) {
                when (credentialHeader) {
                    "Authorization" -> conn.setRequestProperty("Authorization", "Bearer $credential")
                    else -> conn.setRequestProperty(credentialHeader, credential)
                }
            }
            if (!initializing && protocolVersion.isNotBlank()) {
                conn.setRequestProperty("MCP-Protocol-Version", protocolVersion)
                // These standard mirrors are required by the newest Streamable HTTP revision.
                conn.setRequestProperty("Mcp-Method", method)
                if (method == "tools/call") conn.setRequestProperty("Mcp-Name", params.optString("name"))
            }
            sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            if (status in 300..399) throw IllegalStateException("The MCP server redirected the request. Check the HTTPS endpoint and try again.")
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (notification && status == 202) return@withContext JSONObject().put("result", JSONObject())
            if (status !in 200..299) {
                val detail = try { JSONObject(text).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "MCP server returned HTTP $status.")
            }
            val response = parseJsonResponse(text, conn.contentType)
            if (response.has("error")) throw protocolError(response)
            response
        } finally {
            conn.disconnect()
        }
    }

    private fun parseJsonResponse(text: String, contentType: String?): JSONObject {
        if (text.isBlank()) return JSONObject()
        if (contentType?.contains("text/event-stream", ignoreCase = true) == true || text.lineSequence().any { it.startsWith("data:") }) {
            val payloads = text.lineSequence().filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }.filter { it.isNotBlank() && it != "[DONE]" }.toList()
            for (payload in payloads.asReversed()) {
                try {
                    val json = JSONObject(payload)
                    if (json.has("result") || json.has("error")) return json
                } catch (_: Exception) { }
            }
            throw IllegalStateException("MCP server sent an event stream without a final JSON-RPC result.")
        }
        return try { JSONObject(text) } catch (_: Exception) { throw IllegalStateException("MCP server returned an unreadable response.") }
    }

    private fun protocolError(response: JSONObject): IllegalStateException {
        val error = response.optJSONObject("error")
        return IllegalStateException(error?.optString("message")?.takeIf { it.isNotBlank() } ?: "The MCP server response was missing a result.")
    }

    companion object {
        /** Maps only a schema-declared string field to the user's chat text. */
        fun messageArguments(tool: McpTool, message: String): JSONObject {
            val properties = tool.inputSchema.optJSONObject("properties") ?: JSONObject()
            val required = tool.inputSchema.optJSONArray("required") ?: JSONArray()
            val preferred = listOf("message", "request", "prompt", "text", "input", "query", "task")

            fun canSupplyOnly(key: String): Boolean {
                val type = properties.optJSONObject(key)?.optString("type")
                if (type != "string") return false
                for (i in 0 until required.length()) {
                    if (required.optString(i) != key) return false
                }
                return true
            }

            for (candidate in preferred) {
                val key = properties.keys().asSequence().firstOrNull { it.equals(candidate, ignoreCase = true) } ?: continue
                if (canSupplyOnly(key)) return JSONObject().put(key, message)
            }
            if (required.length() == 1) {
                val key = required.optString(0)
                if (canSupplyOnly(key)) return JSONObject().put(key, message)
            }
            throw IllegalStateException("This assistant's message tool has a schema this preview cannot map safely.")
        }

        fun toolText(result: McpCallResult): String {
            val structured = result.result.optJSONObject("structuredContent")
            structured?.let { findReplyText(it)?.let { text -> return text } }
            val content = result.result.optJSONArray("content") ?: JSONArray()
            val texts = buildList {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    if (item.optString("type") == "text") {
                        val value = item.optString("text")
                        if (value.isNotBlank()) add(value)
                    }
                }
            }
            val joined = texts.joinToString("\n").trim()
            if (joined.isBlank()) return "The assistant returned no text."
            val json = try { JSONObject(joined) } catch (_: Exception) { null }
            if (json != null) return findReplyText(json) ?: joined
            return joined
        }

        private fun findReplyText(value: JSONObject): String? {
            for (key in listOf("reply", "answer", "response", "text", "message", "output")) {
                val candidate = value.opt(key)
                if (candidate is String && candidate.isNotBlank()) return candidate
            }
            return null
        }
    }
}
