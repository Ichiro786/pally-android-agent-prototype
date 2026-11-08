# Android agent preview

This is an unofficial, work-in-progress Kotlin and Jetpack Compose Android app. It is not a production-ready agent client.

## Implemented in the current source

- A dark Compose chat UI with faint horizontal scanlines, Pally mark, assistant selection, work drawer and animated voice-orb preview.
- A small HTTPS Streamable HTTP MCP client that initializes a server and lists its advertised tools.
- A Fo by Wajo adapter that looks for the documented `ask_fo` and `get_fo_reply` tools, maps message text only into a string field declared by the server's tool schema, and polls a working response. The app requires a Wajo API key entered on device. A real Wajo account connection has not been tested, so end-to-end Fo operation remains unverified.
- Wajo and Gemini API-key fields use AES-GCM with an Android Keystore key for device-local encrypted storage.
- A local chat preview and thinking/searching animations.

## Not implemented or not verified

- Pally sign-in. The public Pally MCP connector uses OAuth 2.1 with dynamic client registration and PKCE; this app does not yet implement that flow, so Pally replies are unavailable.
- Gemini Live sessions, microphone capture or audio playback. The Gemini key setting stores a key locally, but does not establish a Live connection. A production client should use a backend-issued short-lived token rather than ship a long-lived key.
- Native agent reactions, sending attachments, and durable chat history.
- Generic execution of arbitrary MCP tools. Generic servers can be inspected, but a tool server is not automatically a chat assistant and the app does not run its arbitrary tools.
- Live provider compatibility, device installation, and usability testing.

An MCP connection grants access only to the capabilities the server advertises. Review sensitive tool actions before execution; do not assume universal task support. The Fo message path is provider-specific and should be tested against a real Wajo account before relying on it.

## Build and tests

GitHub Actions uses JDK 17, Android SDK 36 and Gradle 8.13. It runs `:app:assembleDebug :app:testDebugUnitTest` and uploads a debug APK when the build succeeds. There is no Gradle wrapper yet. The workflow fetches the official Pally app icon and verifies its pinned SHA-256 before building. To build locally, install Gradle 8.13 and Android SDK 36, place the official icon at `app/src/main/res/drawable-nodpi/pally_logo.png`, verify SHA-256 `d0f46d83c54386153c12f21472166299babade7139a0dd7c7c9ea915ab7118ea`, then run `gradle :app:assembleDebug :app:testDebugUnitTest`.

A successful Actions run proves only compilation and the tests included in that run; it does not prove provider interoperability, on-device UI quality, or production readiness. Debug APKs are development builds and are not release-signed.

## Security

Never commit tokens, API keys, or personal data to this public repository, and never print secrets in CI logs. Android Keystore storage protects saved local credentials, but it does not replace a backend or guarantee security on a compromised device. Disconnect and erase credentials when no longer needed.

## References

- https://pally.com/mcp
- https://agent.pally.com/.well-known/oauth-authorization-server
- https://wajo.ai/docs/mcp-server.md
- https://wajo.ai/docs/mcp-tools.md
- https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- https://orbkit.zzzzshawn.cloud/
