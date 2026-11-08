# Android agent preview

Unofficial Kotlin and Jetpack Compose Android prototype. This is an experimental debug build, not a production-ready agent app.

## Implemented in source

- Dark chat UI with subtle fading horizontal scanlines, assistant selection, work drawer, long-press reply, emoji picker, an animated Pally mark and a morphing voice-orb preview.
- Pally OAuth 2.1 public-client flow using dynamic registration and S256 PKCE, callback-state validation, encrypted device token storage and refresh. The launcher now routes OAuth callbacks into the chat. The Pally adapter calls only advertised `message_pally` and `check_pally` tools.
- Fo by Wajo adapter for its documented `/fo` profile, using `ask_fo` and `get_fo_reply`, only schema-declared message arguments and server-issued reply handles.
- Chat history and saved Wajo/Gemini credentials encrypted on device with AES-GCM and an Android Keystore key.
- Generic HTTPS MCP tool discovery. Generic MCP servers are not treated as conversational assistants and arbitrary tools are not executed.

The OAuth wiring is in the latest source commit. The current Actions build is still running; a successful compile after this change and account-level sign-in have not yet been verified. The Fo connection has not been tested with a live Wajo account.

## Not implemented

- Gemini Live WebSocket sessions, microphone capture, audio playback and production ephemeral-token provisioning. The preferences screen can save a user-supplied Gemini key encrypted on the device, but it is not used for a Live call. Google's Live documentation recommends backend-issued ephemeral tokens for production clients.
- Native agent reactions. MCP does not itself provide message reactions; no supported reaction capability has been verified for these adapters.
- Attachment upload or sending. The UI explains this limitation; provider attachment contracts are not implemented.
- Phone installation or usability testing.

## Build and tests

GitHub Actions uses JDK 17 and Android SDK 36 to run `:app:assembleDebug` and `:app:testDebugUnitTest`, then uploads a debug APK artifact for 14 days. The runner provides Gradle 9.8.2; the repository does not currently include a Gradle wrapper. Unit tests cover schema-safe MCP argument mapping, Fo pending states and reply-handle matching, and Pally OAuth PKCE/callback validation. They do not test live provider accounts, audio, or UI behavior.

For local builds, install Gradle 9.8.2 and Android SDK 36, place the verified Pally icon at `app/src/main/res/drawable-nodpi/pally_logo.png`, then run `gradle :app:assembleDebug :app:testDebugUnitTest`.

## Security and limits

Never commit API keys, OAuth tokens or personal data. OAuth and provider keys are stored encrypted on this device, but local encryption does not make a long-lived mobile API key safe for production or guarantee protection on a compromised phone. Use a backend-issued short-lived Gemini Live token for production. Disconnect and erase saved credentials when no longer needed. MCP tool support varies by server; a successful discovery does not imply universal task support.

## References

- https://pally.com/mcp
- https://agent.pally.com/.well-known/oauth-authorization-server
- https://wajo.ai/docs/mcp-server.md
- https://wajo.ai/docs/mcp-tools.md
- https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- https://ai.google.dev/gemini-api/docs/live-api
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- https://orbkit.zzzzshawn.cloud/getting-started/usage
