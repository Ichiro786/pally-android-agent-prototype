# Pally Android agent preview

Unofficial Kotlin and Jetpack Compose Android prototype. This is a debug preview, not a production-ready agent app.

## Current implementation

- Dark chat UI with subtle fading horizontal scanlines, assistant picker and work drawer, animated Pally mark, morphing voice orb, reply quoting, emoji input and local encrypted conversation history. The Wajo/Fo header uses a text label because a verified Wajo logo asset is not included.
- Pally OAuth 2.1 public-client flow: dynamic client registration, S256 PKCE, exact callback validation, encrypted device token storage and refresh. The Pally adapter calls the advertised `message_pally` tool. The flow has unit tests, but sign-in and chat have not been tested with a Pally account.
- Fo by Wajo adapter for the documented `/fo` profile. It maps `ask_fo` arguments only when the server schema makes a safe mapping and polls `get_fo_reply` using server-issued handles. A Wajo API key must be entered on the device. No live Wajo account connection has been tested.
- Generic HTTPS MCP tool discovery over Streamable HTTP. A generic MCP server is not automatically a conversational assistant; the app lists capabilities and does not execute arbitrary tools.
- Up to 200 messages per assistant are stored encrypted using an Android Keystore-backed AES-GCM vault. Illustrative first-run messages are excluded from saved history.
- Optional Gemini Live voice path with a morphing orb, runtime microphone permission, direct WebSocket audio session, 16 kHz PCM input and 24 kHz PCM output. It uses the saved device-side Gemini API key. Protocol payload tests pass, but a real Gemini call and device audio have not been tested.

## Verified build

GitHub Actions run 49 completed successfully on November 8, 2026. Its `Compile debug APK and run unit tests` step and APK upload both passed. The test suite checks Fo pending-reply and handle mapping, Gemini Live message encoding, MCP safe argument/reply mapping, and Pally PKCE/callback validation. These tests do not verify provider-account authentication, live network interoperability, audio hardware or UI behavior. The APK has not been tested on a phone.

Download the `android-debug-apk` artifact from the run page: https://github.com/Ichiro786/pally-android-agent-prototype/actions/runs/42480707156. GitHub artifacts expire after 14 days.

## Not implemented / limits

- Native agent reactions are not implemented. MCP does not define a standard message-reaction operation, and no supported reaction contract has been verified for the connected Pally or Fo profiles. The UI must not show a fake native reaction.
- Attachment upload/send is not implemented. The connected providers' verified interfaces in this app do not provide an attachment-send flow; the attachment UI explains that files are not uploaded.
- Production Gemini ephemeral-token provisioning is not included because this repository has no authenticated backend. The local key option is for personal testing only; a device-stored API key can be extracted. Google recommends short-lived ephemeral tokens for direct client connections in production.
- Installation, account sign-in, live Fo/Pally replies, Gemini audio and usability have not been tested on a phone.

## Build

GitHub Actions uses JDK 17 and Android SDK 36, then runs `:app:assembleDebug` and `:app:testDebugUnitTest`. Actions uses its preinstalled Gradle 9.8.2; the repository does not yet include a Gradle wrapper. For a local build, install JDK 17, Android SDK 36 and Gradle 9.8.2, then run:

```sh
gradle :app:assembleDebug :app:testDebugUnitTest
```

## Security

Never commit API keys, OAuth tokens or personal data. Pally tokens, Wajo credentials, Gemini keys and chat history are stored in encrypted Android Keystore-backed storage on the device. Local encryption does not make a long-lived API key production-safe or guarantee protection on a compromised phone. Use a backend-issued ephemeral Gemini token for production. Connect only to trusted MCP endpoints and review server-advertised capabilities.

## References

- Pally MCP: https://pally.com/mcp
- Pally OAuth metadata: https://agent.pally.com/.well-known/oauth-authorization-server
- Wajo MCP server: https://wajo.ai/docs/mcp-server.md
- Wajo MCP tools: https://wajo.ai/docs/mcp-tools.md
- MCP Streamable HTTP: https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- Gemini Live API: https://ai.google.dev/gemini-api/docs/live-api
- Gemini ephemeral tokens: https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- Orbkit usage guide: https://orbkit.zzzzshawn.cloud/getting-started/usage
