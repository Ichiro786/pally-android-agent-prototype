package com.ichiro.pallyagent

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Protocol helpers shared with JVM unit tests. No credentials are included in these payloads. */
internal object GeminiLiveProtocol {
    const val MODEL = "models/gemini-3.8-live"

    fun setupMessage(): String = JSONObject().put("setup", JSONObject()
        .put("model", MODEL)
        .put("responseModalities", JSONArray().put("AUDIO"))
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "You are a helpful voice assistant. Keep spoken replies clear and concise."))))
    ).toString()

    fun audioMessage(pcm16le: ByteArray): String = JSONObject().put("realtimeInput", JSONObject()
        .put("audio", JSONObject()
            .put("mimeType", "audio/pcm;rate=16000")
            .put("data", java.util.Base64.getEncoder().encodeToString(pcm16le))
        )
    ).toString()

    fun audioStreamEndMessage(): String = JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString()
}

/** Experimental direct client-to-Gemini Live session. The API key is supplied at runtime and is never logged. */
internal class GeminiLiveSession(
    private val apiKey: String,
    private val listener: Listener
) {
    interface Listener {
        fun onState(value: String)
        fun onTranscript(text: String, fromModel: Boolean)
        fun onError(message: String)
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val recording = AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    private val output = LinkedBlockingQueue<ByteArray>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    private val stopped = AtomicBoolean(false)

    fun start() {
        require(apiKey.isNotBlank()) { "Add a Gemini API key in voice setup." }
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${android.net.Uri.encode(apiKey)}"
        listener.onState("connecting…")
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(GeminiLiveProtocol.setupMessage())) fail("Could not send Gemini session setup.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val event = JSONObject(text)
                    if (event.has("setupComplete")) startAudio()
                    val serverContent = event.optJSONObject("serverContent") ?: return
                    val inputText = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty()
                    if (inputText.isNotBlank()) listener.onTranscript(inputText, false)
                    val outputText = serverContent.optJSONObject("outputTranscription")?.optString("text").orEmpty()
                    if (outputText.isNotBlank()) listener.onTranscript(outputText, true)
                    val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts") ?: JSONArray()
                    for (index in 0 until parts.length()) {
                        val inline = parts.optJSONObject(index)?.optJSONObject("inlineData") ?: continue
                        val encoded = inline.optString("data")
                        if (encoded.isNotBlank()) {
                            output.offer(java.util.Base64.getDecoder().decode(encoded))
                            listener.onState("speaking")
                        }
                    }
                    if (serverContent.optBoolean("turnComplete")) listener.onState("listening")
                    if (serverContent.optBoolean("interrupted")) {
                        output.clear()
                        player?.let { runCatching { it.pause(); it.flush(); it.play() } }
                        listener.onState("listening")
                    }
                } catch (_: Exception) {
                    fail("Gemini Live returned an unreadable event.")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                fail("Gemini Live returned an unexpected binary frame.")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                stopAudio()
                if (!stopped.get()) listener.onError("Gemini Live connection failed${response?.code?.let { " (HTTP $it)" }.orEmpty()}. Check the API key, model access and network.")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopAudio()
                listener.onState("call ended")
            }
        })
    }

    private fun startAudio() {
        if (stopped.get() || recording.get()) return
        try {
            val inputRate = 16_000
            val inputMin = AudioRecord.getMinBufferSize(inputRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(inputMin > 0) { "This device could not initialize microphone audio." }
            val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, inputRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inputMin * 2, 6_400))
            check(input.state == AudioRecord.STATE_INITIALIZED) { "This device could not initialize microphone audio." }

            val outputRate = 24_000
            val outputMin = AudioTrack.getMinBufferSize(outputRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(outputMin > 0) { "This device could not initialize audio playback." }
            val outputTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(outputRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(outputMin * 2, 12_000))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(outputTrack.state == AudioTrack.STATE_INITIALIZED) { "This device could not initialize audio playback." }

            recorder = input
            player = outputTrack
            input.startRecording()
            outputTrack.play()
            recording.set(true)
            playing.set(true)
            listener.onState("listening")

            thread(name = "gemini-live-microphone") {
                val chunk = ByteArray(3_200) // 100 ms of mono 16 kHz PCM16
                try {
                    while (recording.get()) {
                        val count = input.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                        if (count > 0 && recording.get()) {
                            val pcm = if (count == chunk.size) chunk.copyOf() else chunk.copyOf(count)
                            if (socket?.send(GeminiLiveProtocol.audioMessage(pcm)) != true) break
                        }
                    }
                } catch (_: Exception) {
                    if (recording.get()) fail("Microphone capture stopped unexpectedly.")
                }
            }

            thread(name = "gemini-live-playback") {
                try {
                    while (playing.get() || output.isNotEmpty()) {
                        val frame = output.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        var offset = 0
                        while (offset < frame.size && playing.get()) {
                            val written = outputTrack.write(frame, offset, frame.size - offset, AudioTrack.WRITE_BLOCKING)
                            if (written <= 0) break
                            offset += written
                        }
                    }
                } catch (_: Exception) {
                    if (playing.get()) fail("Audio playback stopped unexpectedly.")
                }
            }
        } catch (e: Exception) {
            stopAudio()
            fail(e.message ?: "Could not start Gemini Live audio.")
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        recording.set(false)
        socket?.send(GeminiLiveProtocol.audioStreamEndMessage())
        stopAudio()
        socket?.close(1000, "voice call ended")
        socket = null
        client.dispatcher.executorService.shutdown()
        listener.onState("call ended")
    }

    private fun fail(message: String) {
        if (stopped.compareAndSet(false, true)) {
            stopAudio()
            socket?.close(1011, "voice session ended")
            client.dispatcher.executorService.shutdown()
            listener.onError(message)
        }
    }

    private fun stopAudio() {
        recording.set(false)
        playing.set(false)
        output.clear()
        recorder?.let { audio -> runCatching { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }; runCatching { audio.release() } }
        recorder = null
        player?.let { audio -> runCatching { audio.pause() }; runCatching { audio.flush() }; runCatching { audio.stop() }; runCatching { audio.release() } }
        player = null
    }
}
