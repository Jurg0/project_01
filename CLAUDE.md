# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android multiplayer game app where up to 20 smartphones connect via Wi-Fi Direct. One or more devices act as hidden "game masters" controlling video playback, screen state, and torch on all connected player devices. Players walk through a woods-based narrative experience. Game masters must remain undercover — their UI is identical to players but with invisible controls.

## Build & Test Commands

```bash
# Build debug APK
./project_01_android/gradlew -p ./project_01_android assembleDebug

# Run all unit tests
./project_01_android/gradlew -p ./project_01_android test

# Run a single test class
./project_01_android/gradlew -p ./project_01_android testDebugUnitTest --tests "com.project01.GameViewModelTest"

# APK output location
# project_01_android/app/build/outputs/apk/debug/
```

## Architecture

Single-activity MVVM architecture written entirely in Kotlin.

### Layer Diagram

```
MainActivity (View)
    ↓
GameViewModel (ViewModel - UI logic, P2P, Bluetooth, video management)
    ↓
GameRepository (Repository - Android system services, Wi-Fi P2P)
    ↓
GameSync (Facade over NetworkManager)
    ↓
SocketNetworkManager (TCP sockets, implements NetworkManager interface)
FileTransfer (Binary file transfer, separate TCP connection)
```

### Networking Stack

Three layers handle connectivity:

1. **Wi-Fi Direct (P2P layer):** `p2p/ConnectionService.kt` and `p2p/WifiDirectBroadcastReceiver.kt` manage device discovery and P2P connections using Android's WifiP2pManager API. ConnectionService is a background service that maintains the connection when the screen is off.

2. **Game state sync (Session layer):** `GameSync` wraps `NetworkManager` (interface) with `SocketNetworkManager` as the TCP socket implementation. Server listens on port 8888. Uses kotlinx.serialization JSON with a 4-byte length-prefixed wire format (`MessageEnvelope` for encode/decode). All network messages implement the `GameMessage` sealed interface. Broadcasts messages to all connected clients via a `clients: Map<String, OutputStream>`.

3. **File transfer:** `FileTransfer` uses a separate `ServerSocket` for binary transfers with an 8-byte file size header followed by content. Emits progress/success/failure events via Flow.

### Key Data Flow

- Game master creates a game → starts TCP server → other devices connect via Wi-Fi Direct
- Players join with a password via challenge-response: server sends `PasswordChallenge(nonce)`, client replies with `PasswordMessage(SHA-256(password + nonce))`, server verifies and sends `PasswordResponseMessage(success)`
- Game master broadcasts: `PlaybackCommand` (PLAY_PAUSE, NEXT, PREVIOUS), `PlaybackState` (sync position), `AdvancedCommand` (TURN_OFF_SCREEN, DEACTIVATE_TORCH), video playlists as `VideoListMessage`
- Clients auto-reconnect on disconnect via `ReconnectionManager` (exponential backoff with jitter, max 10 retries)
- Videos are transferred to player devices' local storage via FileTransfer so playback works on slow/intermittent connections

### Reactive Patterns

- `SocketNetworkManager.events` and `FileTransfer` emit events via Kotlin `Flow`
- `NetworkEvent` is a sealed class for typed network events
- ViewModel exposes state to the View via `LiveData`
- Coroutines with `SupervisorJob` used throughout the networking layer

## Tech Stack

- **Min SDK 24 / Target SDK 34**, Compile SDK 34, Java target 11
- **Kotlin 1.9.22**, Android Gradle Plugin 8.4.1, Gradle 9.2.1
- **Serialization:** kotlinx.serialization 1.6.2 (JSON)
- **Video:** ExoPlayer (media3 1.2.1)
- **UI:** XML layouts with ConstraintLayout, Material Design
- **Navigation:** AndroidX Navigation (fragment-based)
- **Testing:** JUnit 4, Mockito + Mockito-Kotlin, Robolectric, Turbine (Flow testing), kotlinx-coroutines-test

## Development Conventions

- All source code is under `project_01_android/app/src/main/java/com/project01/`
- All network message types implement `GameMessage` sealed interface with `@Serializable` annotations; `classDiscriminator = "msg_type"` (not "type" — clashes with PlaybackCommand's `type` field)
- `VideoDto` bridges `Video` (uses Android `Uri`) to serializable form; extension functions `Video.toDto()` / `VideoDto.toVideo()` in Video.kt
- Data classes use `@Parcelize` (Player, Video)
- `GameRepository` constructs dependencies directly (no DI framework): `GameSync(SocketNetworkManager())`, `FileTransfer()`
- `GameViewModel` takes `GameRepository` as a default constructor parameter
- `TestNetworkManager` in the test directory provides a mock `NetworkManager` for unit tests
- `PasswordHasher` object handles nonce generation (`SecureRandom`) and SHA-256 hashing
- `ReconnectionManager` uses `StateFlow<ReconnectionState>` observed by GameViewModel via `collectLatest`
- App version derived from git tags at build time (`build.gradle`); `versionName` from latest `v*` tag, `versionCode` from tag count
- Permissions are currently handled with `@SuppressLint("MissingPermission")` — explicit runtime permission handling is planned

## Releases

- Tag-based releases: `./release.sh v1.0.0 "Optional message"` creates a git tag and pushes it
- GitHub Actions workflow (`.github/workflows/release.yml`) builds the APK and creates a GitHub release on tag push
- Release notes auto-generated from commit messages since the last tag

## Known Technical Debt

See `IMPLEMENTATION_PLAN.md` for the full improvement roadmap. Key remaining items:
- Error handling uses `e.printStackTrace()` — needs proper logging and UI error feedback
- Runtime permissions need explicit handling
- ConnectionService needs foreground service conversion for battery/doze resilience
- FileTransfer lacks checksum validation and retry logic
- Test coverage is incomplete, especially for UI and networking logic
