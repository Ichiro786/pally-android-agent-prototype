package com.ichiro.pallyagent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoAdapterTest {
    @Test fun recognizesPendingFoReplies() {
        assertTrue(FoAdapter.isWorking(JSONObject("""{"status":"working"}""")))
        assertTrue(FoAdapter.isWorking(JSONObject("""{"state":"pending"}""")))
        assertFalse(FoAdapter.isWorking(JSONObject("""{"status":"completed"}""")))
        assertFalse(FoAdapter.isWorking(null))
    }

    @Test fun mapsOnlyMatchingServerIssuedHandleName() {
        val poll = tool("""{"type":"object","properties":{"request_id":{"type":"string"}},"required":["request_id"]}""")
        val args = FoAdapter.pollingArguments(poll, JSONObject("""{"requestId":"issued-123"}"""))
        assertEquals("issued-123", args?.getString("request_id"))
    }

    @Test fun refusesToGuessBetweenDifferentIdentifierKinds() {
        val poll = tool("""{"type":"object","properties":{"request_id":{"type":"string"}},"required":["request_id"]}""")
        assertNull(FoAdapter.pollingArguments(poll, JSONObject("""{"conversation_id":"conversation-42"}""")))
    }

    @Test fun genericHandleAcceptsAnIssuedIdWhenSchemaUsesHandle() {
        val poll = tool("""{"type":"object","properties":{"handle":{"type":"string"}},"required":["handle"]}""")
        val args = FoAdapter.pollingArguments(poll, JSONObject("""{"request_id":"issued-123"}"""))
        assertEquals("issued-123", args?.getString("handle"))
    }

    @Test fun refusesPollWhenRequiredHandleIsMissing() {
        val poll = tool("""{"type":"object","properties":{"task_id":{"type":"string"}},"required":["task_id"]}""")
        assertNull(FoAdapter.pollingArguments(poll, JSONObject("""{"request_id":"issued-123"}""")))
    }

    private fun tool(schema: String) = McpTool("get_fo_reply", "poll Fo reply", JSONObject(schema))
}