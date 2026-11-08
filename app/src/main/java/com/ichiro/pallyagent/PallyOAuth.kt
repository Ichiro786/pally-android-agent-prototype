package com.ichiro.pallyagent

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** Native public-client OAuth for Pally's documented MCP endpoint. */
object PallyOAuth {
    const val MCP_ENDPOINT = "https://agent.pally.com/mcp"
    internal const val REDIRECT_URI = "pallyagent://oauth/callback"
    private const val ISSUER = "https://agent.pally.com"
    private const val DISCOVERY = "$ISSUER/.well-known/oauth-authorization-server"
    private const val ACCESS = "pally_oauth_access"
    private const val REFRESH = "pally_oauth_refresh"
    private const val VERIFIER = "pally_oauth_verifier"
    private const val STATE = "pally_oauth_state"
    private const val CLIENT = "pally_oauth_client_id"
    private const val EXPIRES = "pally_oauth_expires_at"
    private const val PREFS = "agent_state"

    /** Registers a public client if needed, persists a one-use PKCE verifier, and returns the browser URL. */
    suspend fun begin(context: Context): String = withContext(Dispatchers.IO) {
        val metadata = discovery()
        require(metadata.optJSONArray("response_types_supported")?.let { array -> (0 until array.length()).any { array.optString(it) == "code" } } == true) { "Pally OAuth does not advertise the authorization-code flow." }
        require(metadata.optJSONArray("code_challenge_methods_supported")?.let { array -> (0 until array.length()).any { array.optString(it) == "S256" } } == true) { "Pally OAuth does not advertise S256 PKCE." }
        val clientId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLIENT, null)
            ?: registerClient(metadata).also { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CLIENT, it).apply() }
        val verifier = randomUrlToken(32)
        val state = randomUrlToken(32)
        SecretVault.save(context, VERIFIER, verifier)
        SecretVault.save(context, STATE, state)
        Uri.parse(metadata.getString("authorization_endpoint")).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "mcp")
            .appendQueryParameter("resource", MCP_ENDPOINT)
            .appendQueryParameter("code_challenge", pkceChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build().toString()
    }

    /** Validates the one-use state and exchanges the code; access and refresh tokens stay in the device vault. */
    suspend fun finish(context: Context, callback: Uri): String = withContext(Dispatchers.IO) {
        if (!isRedirect(callback)) throw IllegalArgumentException("This is not the Pally sign-in callback.")
        val expected = SecretVault.read(context, STATE)
        val verifier = SecretVault.read(context, VERIFIER)
        val actual = callback.getQueryParameter("state")
        if (expected.isNullOrBlank() || verifier.isNullOrBlank() || actual.isNullOrBlank() ||
            !MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))) {
            clearPending(context)
            throw IllegalStateException("Pally sign-in state did not match. Start sign-in again.")
        }
        val providerError = callback.getQueryParameter("error")
        if (!providerError.isNullOrBlank()) {
            val description = callback.getQueryParameter("error_description")
            clearPending(context)
            throw IllegalStateException(description ?: "Pally sign-in was cancelled ($providerError).")
        }
        val code = callback.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            ?: run { clearPending(context); throw IllegalStateException("Pally did not return an authorization code.") }
        try {
            val metadata = discovery()
            val clientId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLIENT, null)
                ?: throw IllegalStateException("Pally client registration is missing. Start sign-in again.")
            val response = postForm(metadata.getString("token_endpoint"), linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to clientId,
                "code_verifier" to verifier,
                "resource" to MCP_ENDPOINT
            ))
            storeTokens(context, response, null)
            SecretVault.read(context, ACCESS) ?: throw IllegalStateException("Pally returned no access token.")
        } finally {
            clearPending(context)
        }
    }

    /** Returns a valid saved access token, refreshing it when it is close to expiry. */
    suspend fun accessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val access = SecretVault.read(context, ACCESS) ?: return@withContext null
        val expiresAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(EXPIRES, 0L)
        if (expiresAt > System.currentTimeMillis() + 60_000L) return@withContext access
        val refresh = SecretVault.read(context, REFRESH) ?: return@withContext null
        try {
            val metadata = discovery()
            val clientId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CLIENT, null)
                ?: throw IllegalStateException("Pally client registration is missing.")
            val response = postForm(metadata.getString("token_endpoint"), linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to clientId,
                "resource" to MCP_ENDPOINT
            ))
            storeTokens(context, response, refresh)
            SecretVault.read(context, ACCESS)
        } catch (e: Exception) {
            SecretVault.clear(context, ACCESS)
            SecretVault.clear(context, REFRESH)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(EXPIRES).apply()
            throw e
        }
    }

    fun disconnect(context: Context) {
        listOf(ACCESS, REFRESH, VERIFIER, STATE).forEach { SecretVault.clear(context, it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(EXPIRES).remove(CLIENT).apply()
    }

    internal fun isRedirect(uri: Uri): Boolean = uri.scheme == "pallyagent" && uri.host == "oauth" && uri.path == "/callback"

    internal fun pkceChallenge(verifier: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun randomUrlToken(size: Int): String = ByteArray(size).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    private fun discovery(): JSONObject {
        val response = getJson(DISCOVERY)
        require(response.optString("issuer") == ISSUER) { "Pally OAuth discovery returned an unexpected issuer." }
        listOf("authorization_endpoint", "token_endpoint", "registration_endpoint").forEach { key ->
            val endpoint = response.optString(key)
            require(isOfficialEndpoint(endpoint)) { "Pally OAuth discovery returned an unexpected $key." }
        }
        val methods = response.optJSONArray("token_endpoint_auth_methods_supported")
        require(methods != null && (0 until methods.length()).any { methods.optString(it) == "none" }) { "Pally OAuth does not advertise public-client authentication." }
        return response
    }

    private fun isOfficialEndpoint(value: String): Boolean = try {
        val url = URL(value)
        url.protocol == "https" && url.host == "agent.pally.com" && url.userInfo == null
    } catch (_: Exception) { false }

    private fun registerClient(metadata: JSONObject): String {
        val body = JSONObject()
            .put("client_name", "Pally Android Agent Preview")
            .put("redirect_uris", org.json.JSONArray().put(REDIRECT_URI))
            .put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", org.json.JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("scope", "mcp")
        val response = postJson(metadata.getString("registration_endpoint"), body)
        return response.optString("client_id").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Pally's registration endpoint returned no client_id.")
    }

    private fun storeTokens(context: Context, response: JSONObject, priorRefresh: String?) {
        val access = response.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Pally returned no access_token.")
        require(response.optString("token_type").equals("Bearer", true)) { "Pally returned an unsupported token type." }
        val lifetime = response.optLong("expires_in", 0L)
        require(lifetime > 0L) { "Pally returned no valid token expiry." }
        SecretVault.save(context, ACCESS, access)
        val refresh = response.optString("refresh_token").takeIf { it.isNotBlank() } ?: priorRefresh
        if (refresh != null) SecretVault.save(context, REFRESH, refresh)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(EXPIRES, System.currentTimeMillis() + lifetime.coerceAtMost(31_536_000L) * 1000L).apply()
    }

    private fun clearPending(context: Context) {
        SecretVault.clear(context, VERIFIER)
        SecretVault.clear(context, STATE)
    }

    private fun getJson(endpoint: String): JSONObject = request(endpoint, "GET", null)

    private fun postJson(endpoint: String, body: JSONObject): JSONObject = request(endpoint, "POST", body.toString().toByteArray(Charsets.UTF_8), "application/json")

    private fun postForm(endpoint: String, values: Map<String, String>): JSONObject {
        val encoded = values.entries.joinToString("&") { (key, value) -> form(key) + "=" + form(value) }
        return request(endpoint, "POST", encoded.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded")
    }

    private fun form(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(endpoint: String, method: String, body: ByteArray?, contentType: String? = null): JSONObject {
        require(isOfficialEndpoint(endpoint)) { "Refusing an untrusted Pally OAuth endpoint." }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error_description").ifBlank { JSONObject(text).optString("message") } }.getOrNull()
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Pally OAuth returned HTTP $status.")
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}

/** Buffers the browser deep link until the Compose collector is ready. */
object PallyOAuthCallback {
    val events = kotlinx.coroutines.channels.Channel<Uri>(kotlinx.coroutines.channels.Channel.UNLIMITED)
}
