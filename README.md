# Android agent preview

This is an unofficial, work-in-progress Kotlin and Jetpack Compose Android prototype. It is not a finished agent app or a production release.

## Current scope

The source includes a dark chat layout with faint horizontal scanlines, a Pally header, identity selection, preview thinking/searching motion, and a visual voice-orb screen. Message text remains in memory on the device while the process is alive. The Fo header is a text placeholder, not a verified Wajo brand asset. This code has not been verified on a device.

Assistant connections, Pally OAuth, generic MCP transport, Fo chat, real agent replies, native reactions, attachments, persistence, microphone audio, Gemini Live and API-key storage are not implemented. The UI says when it is a preview; do not enter credentials into its endpoint form. An MCP tool server is not necessarily a chat assistant. Reactions require provider support.

## Build

The Android debug GitHub Actions workflow uses JDK 17, Android SDK 36 and Gradle 8.13, then runs `:app:assembleDebug :app:testDebugUnitTest` and uploads the debug APK only if the build succeeds. The workflow fetches the Pally app icon from its official brand URL and verifies a pinned SHA-256 before compiling. No Gradle wrapper is included yet. To build locally with an installed Gradle 8.13 and the Android SDK, place the official icon at `app/src/main/res/drawable-nodpi/pally_logo.png`, verify its SHA-256 is `d0f46d83c54386153c12f21472166299babade7139a0dd7c7c9ea915ab7118ea`, then run `gradle :app:assembleDebug :app:testDebugUnitTest`.

An Actions success indicates compilation and whatever automated tests exist, not that voice, auth, integrations or the UI have been tested on a phone. Debug APKs are intended for development and are not release-signed.

## Security

Never commit tokens, API keys or user data to this public repository or print them in CI. Gemini recommends short-lived ephemeral tokens for client-side Live API use. Authentication, authorization, sensitive-action review, data handling, encrypted storage and provider capability handling are still to be designed, implemented and tested before real use.

## References

- https://pally.com/mcp
- https://pally.com/brand/app-icon.png
- https://wajo.ai/docs/mcp-server.md
- https://wajo.ai/docs/mcp-tools.md
- https://kotlin.sdk.modelcontextprotocol.io/
- https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens
- https://orbkit.zzzzshawn.cloud/
