package com.ichiro.pallyagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class McpClientMappingTest {
    @Test fun mapsTextToSchemaDeclaredMessageField() {
        val tool = tool("""{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""")
        val args = McpClient.messageArguments(tool, "hello Fo")
        assertEquals("hello Fo", args.getString("message"))
        assertEquals(1, args.length())
    }

    @Test fun usesOnlySingleRequiredStringAsSafeFallback() {
        val tool = tool("""{"type":"object","properties":{"task_text":{"type":"string"}},"required":["task_text"]}""")
        val args = McpClient.messageArguments(tool, "check this")
        assertEquals("check this", args.getString("task_text"))
    }

    @Test fun rejectsAmbiguousRequiredFields() {
        val tool = tool("""{"type":"object","properties":{"task":{"type":"string"},"account":{"type":"string"}},"required":["task","account"]}""")
        assertThrows(IllegalStateException::class.java) { McpClient.messageArguments(tool, "do it") }
    }

    @Test fun extractsStructuredReplyText() {
        val result = McpCallResult(JSONObject().put("structuredContent", JSONObject().put("reply", "done")), false)
        assertEquals("done", McpClient.toolText(result))
    }

    @Test fun extractsTextContentWhenNoStructuredReplyExists() {
        val result = McpCallResult(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Fo replied"))), false)
        assertEquals("Fo replied", McpClient.toolText(result))
    }

    private fun tool(schema: String) = McpTool("ask_fo", "test tool", JSONObject(schema))
}
