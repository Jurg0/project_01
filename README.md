[![CI](https://github.com/Jurg0/project_01/actions/workflows/ci.yml/badge.svg)](https://github.com/Jurg0/project_01/actions/workflows/ci.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPL_v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Latest Release](https://img.shields.io/github/v/release/Jurg0/project_01?color=blue&label=version)](https://github.com/Jurg0/project_01/releases)

# Project 01 — Wi-Fi Direct Multiplayer Game

Android multiplayer game app where up to 20 smartphones connect via Wi-Fi Direct. One or more devices act as hidden "game masters" controlling video playback, screen state, and torch on all connected player devices. Players walk through a woods-based narrative experience. Game masters remain undercover — their UI is identical to players but with invisible controls.

## Prerequisites

- **Android Studio** (Arctic Fox or later recommended)
- **JDK 17** or later
- **Android SDK 34** (compile and target SDK)
- **Min SDK 24** (Android 7.0)

## Building

### Debug APK

From the repository root:

```bash
./project_01_android/gradlew -p ./project_01_android assembleDebug
```

The debug APK is output to:

```
project_01_android/app/build/outputs/apk/debug/app-debug.apk
```

### Release APK

```bash
./project_01_android/gradlew -p ./project_01_android assembleRelease
```

> **Note:** Release builds require a signing configuration. See `IMPLEMENTATION_PLAN.md` Priority 16 for setup instructions.

## Running Tests

Run all unit tests:

```bash
./project_01_android/gradlew -p ./project_01_android test
```

Run a single test class:

```bash
./project_01_android/gradlew -p ./project_01_android testDebugUnitTest --tests "com.project01.GameViewModelTest"
```

## Architecture

Single-activity MVVM architecture written entirely in Kotlin.

```
MainActivity (View)
    ↓
GameViewModel (ViewModel — UI logic, P2P, Bluetooth, video management)
    ↓
GameRepository (Repository — Android system services, Wi-Fi P2P)
    ↓
GameSync (Facade over NetworkManager)
    ↓
SocketNetworkManager (TCP sockets, implements NetworkManager interface)
FileTransfer (Binary file transfer, separate TCP connection)
```

### Networking

Three layers handle connectivity:

1. **Wi-Fi Direct (P2P layer):** Device discovery and group formation via Android's `WifiP2pManager` API. `ConnectionService` maintains the connection when the screen is off.

2. **Game state sync (Session layer):** `SocketNetworkManager` implements TCP socket communication on port 8888. Uses a 4-byte length-prefixed JSON wire format via `kotlinx.serialization`. All message types implement the `GameMessage` sealed interface for compile-time type safety.

3. **File transfer:** `FileTransfer` uses a separate `ServerSocket` for binary video transfers with an 8-byte file size header. Emits progress/success/failure events via Kotlin `Flow`.

### Key Data Flow

- Game master creates a game and starts a TCP server
- Players connect via Wi-Fi Direct and authenticate with a password
- Game master broadcasts: `PlaybackCommand` (play/pause, next, previous), `PlaybackState` (position sync), `AdvancedCommand` (screen off, torch off), and video playlists
- Videos are transferred to player devices' local storage so playback works on intermittent connections

## Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin 1.9.22 |
| Build | AGP 8.4.1, Gradle 9.2.1 |
| Serialization | kotlinx.serialization 1.6.2 |
| Video playback | ExoPlayer (media3 1.2.1) |
| UI | XML layouts, ConstraintLayout, Material Design, ViewBinding |
| Navigation | AndroidX Navigation (fragment-based) |
| Reactive | Kotlin Coroutines + Flow, LiveData |
| Testing | JUnit 4, Mockito, Robolectric, Turbine, kotlinx-coroutines-test |

## Project Structure

```
project_01_android/app/src/main/java/com/project01/
├── viewmodel/
│   └── GameViewModel.kt          # UI logic, P2P, Bluetooth, video management
├── session/
│   ├── GameMessage.kt             # Sealed interface for all network messages
│   ├── MessageEnvelope.kt         # JSON wire format encoder/decoder
│   ├── SocketNetworkManager.kt    # TCP socket implementation
│   ├── NetworkManager.kt          # Network abstraction interface
│   ├── NetworkEvent.kt            # Typed network events (sealed class)
│   ├── GameSync.kt                # Facade over NetworkManager
│   ├── FileTransfer.kt            # Binary file transfer
│   ├── PlaybackCommand.kt         # Play/pause/next/previous commands
│   ├── PlaybackState.kt           # Video position synchronization
│   ├── AdvancedCommand.kt         # Screen and torch control
│   ├── PasswordMessage.kt         # Game join authentication
│   ├── PasswordResponseMessage.kt # Authentication response
│   ├── FileTransferRequest.kt     # File transfer initiation
│   └── Video.kt                   # Video data model
├── p2p/
│   ├── ConnectionService.kt       # Background service for Wi-Fi Direct
│   └── WifiDirectBroadcastReceiver.kt
├── bluetooth/
│   └── BluetoothRemoteControl.kt  # Bluetooth remote control support
├── GameRepository.kt              # Android system services, dependency construction
├── MainActivity.kt                # Single activity (View layer)
├── Player.kt                      # Player data model
├── PlayerAdapter.kt               # RecyclerView adapter for player list
└── VideoAdapter.kt                # RecyclerView adapter for video playlist
```

## Creating a Release

Releases are automated via GitHub Actions. When you push a version tag, CI builds the APK and creates a GitHub release with it attached.

### Using the release script

```bash
./release.sh v1.0.0 "Initial release"
```

The script:
1. Validates the version format (`vX.Y.Z`)
2. Checks for uncommitted changes
3. Builds the debug APK locally as a sanity check
4. Creates an annotated git tag
5. Pushes the tag to origin, which triggers GitHub Actions

### Manual steps (equivalent)

```bash
git tag -a v1.0.0 -m "Initial release"
git push origin v1.0.0
```

### What happens on GitHub

The `.github/workflows/release.yml` workflow triggers on any `v*` tag push. It builds the debug APK in CI and creates a GitHub release with the APK attached as a downloadable asset.

Once release signing is configured (see `IMPLEMENTATION_PLAN.md` Priority 16), the workflow can be extended to also build and attach a signed release APK.

## Roadmap

See `IMPLEMENTATION_PLAN.md` for the full improvement roadmap with detailed implementation plans for each priority.
