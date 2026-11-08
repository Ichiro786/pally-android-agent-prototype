package com.ichiro.pallyagent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveProtocolTest {
    @Test fun setupUsesDocumentedLiveModelAndAudioResponse() {
        val setup = JSONObject(GeminiLiveProtocol.setupMessage()).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals("AUDIO", setup.getJSONArray("responseModalities").getString(0))
        assertTrue(setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text").isNotBlank())
    }

    @Test fun microphoneAudioIsBase64Pcm16At16000Hz() {
        val audio = JSONObject(GeminiLiveProtocol.audioMessage(byteArrayOf(1, 2, 3)))
            .getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("AQID", audio.getString("data"))
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
    }

    @Test fun sessionEndSignalsAudioStreamEnd() {
        assertTrue(JSONObject(GeminiLiveProtocol.audioStreamEndMessage())
            .getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))
    }
}
