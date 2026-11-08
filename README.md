# Android agent preview

Unofficial Kotlin and Jetpack Compose Android prototype. This is a debug build, not a production-ready agent app.

## Current features

- Dark chat screen with fading horizontal scanlines, assistant selection, a work drawer, sample conversation labelled as illustrative, long-press reply, emoji picker, and voice entry.
- Animated Pally logo indicator and an Orbkit-inspired original Canvas orb animation. These animations are visual previews.
- Streamable HTTP MCP client for HTTPS endpoint initialization and server-advertised tool discovery.
- Fo by Wajo adapter for the documented `ask_fo` / `get_fo_reply` profile. It maps a message only to a schema-declared string field, maps a returned reply handle only to a compatible ID field, and refuses missing required arguments. The Wajo account flow has not been tested end to end.
- Wajo and Gemini key fields use Android Keystore-backed AES-GCM device-local storage.

## Not implemented or not verified

- Pally OAuth sign-in and live replies are not implemented. The Pally MCP page and OAuth discovery are documented, but this app does not complete OAuth 2.1 with PKCE.
- Fo interoperation against a live Wajo account is unverified. Generic MCP servers can be inspected; arbitrary tool execution and universal assistant behavior are not supported.
- Gemini Live sessions, microphone capture and audio playback are not implemented. The Gemini key field only saves a local key; production should use backend-issued short-lived tokens.
- Native agent reactions, attachment sending, and durable chat history are not implemented.
- No phone installation or usability testing has been performed. A CI build only verifies the configured compile and unit-test tasks.

MCP capabilities vary by server. The app must not execute a tool unless it is advertised and the user has approved any sensitive action.

## Build and tests

GitHub Actions uses JDK 17, Android SDK 36, and the runner-provided Gradle 9.8.2. The repository currently has no Gradle wrapper. Actions runs `gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest` and uploads a debug APK artifact for 14 days. Unit tests cover schema-safe message mapping, reply text extraction, Fo pending states, and safe matching of issued reply handles to the server's poll schema; they do not test UI behavior or live providers. The workflow downloads the official Pally app icon and checks its pinned SHA-256 before building.

A manual workflow dispatch can optionally accept a temporary source URL for the approved chat activity. That file is accepted only from `my.pally.com` and only when its pinned SHA-256 matches. It is committed by a separate, narrowly permissioned job before the build job checks out the resulting commit. Normal pull-request builds have read-only repository permissions.

For a local build, install Gradle 9.8.2 and Android SDK 36, place the official icon at `app/src/main/res/drawable-nodpi/pally_logo.png`, verify SHA-256 `d0f46d83c54386153c12f21472166299babade7139a0dd7c7c9ea915ab7118ea`, then run `gradle :app:assembleDebug :app:testDebugUnitTest`.

## Security

Never commit API keys, tokens or personal data. The device-local encrypted credential fields do not replace a secure backend or guarantee safety on a compromised phone. Do not paste keys in chat. Disconnect and erase saved credentials when no longer needed.

## References

- https://pally.com/mcp
- https://agent.pally.com/.well-known/oauth-authorization-server
- https://wajo.ai/docs/mcp-server.md
- https://wajo.ai/docs/mcp-tools.md
- https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- https://orbkit.zzzzshawn.cloud/