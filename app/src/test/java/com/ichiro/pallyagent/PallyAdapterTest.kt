package com.ichiro.pallyagent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PallyAdapterTest {
    @Test fun mapsMessageOnlyToAdvertisedRequiredString() {
        val tool = tool("message_pally", """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}""")
        assertEquals("hello", McpClient.messageArguments(tool, "hello").getString("message"))
    }

    @Test fun refusesAdditionalRequiredMessageFields() {
        val tool = tool("message_pally", """{"type":"object","properties":{"message":{"type":"string"},"account_id":{"type":"string"}},"required":["message","account_id"]}""")
        try { McpClient.messageArguments(tool, "hello"); throw AssertionError("should refuse an unknown required field") }
        catch (_: IllegalStateException) { }
    }

    @Test fun mapsOnlyTheIssuedRequestHandleForPolling() {
        val poll = tool("check_pally", """{"type":"object","properties":{"request_id":{"type":"string"}},"required":["request_id"]}""")
        val args = PallyAdapter.pollingArguments(poll, JSONObject("""{"status":"pending","request_id":"issued-42"}"""))
        assertEquals("issued-42", args?.getString("request_id"))
    }

    @Test fun refusesToGuessMissingOrExtraRequiredPollFields() {
        val poll = tool("check_pally", """{"type":"object","properties":{"request_id":{"type":"string"},"account_id":{"type":"string"}},"required":["request_id","account_id"]}""")
        assertNull(PallyAdapter.pollingArguments(poll, JSONObject("""{"request_id":"issued-42"}""")))
    }

    @Test fun genericHandleRequiresUnambiguousIssuedId() {
        val poll = tool("check_pally", """{"type":"object","properties":{"handle":{"type":"string"}},"required":["handle"]}""")
        assertEquals("issued-42", PallyAdapter.pollingArguments(poll, JSONObject("""{"request_id":"issued-42"}"""))?.getString("handle"))
        assertNull(PallyAdapter.pollingArguments(poll, JSONObject("""{"request_id":"issued-42","task_id":"task-9"}""")))
    }

    @Test fun recognizesOnlyPendingPollStates() {
        assertTrue(PallyAdapter.isPending(JSONObject("""{"status":"working"}""")))
        assertTrue(PallyAdapter.isPending(JSONObject("""{"state":"queued"}""")))
        assertFalse(PallyAdapter.isPending(JSONObject("""{"status":"completed"}""")))
        assertFalse(PallyAdapter.isPending(null))
    }

    private fun tool(name: String, schema: String) = McpTool(name, "test", JSONObject(schema))
}
