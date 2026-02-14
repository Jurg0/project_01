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

Release builds require a signing keystore. See [Setting Up Release Signing](#setting-up-release-signing) below.

The signed release APK is output to:

```
project_01_android/app/build/outputs/apk/release/app-release.apk
```

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

1. **Wi-Fi Direct (P2P layer):** Device discovery and group formation via Android's `WifiP2pManager` API. `ConnectionService` runs as a foreground service to maintain the Wi-Fi Direct connection through screen-off and doze mode.

2. **Game state sync (Session layer):** `SocketNetworkManager` implements TCP socket communication on port 8888. Uses a 4-byte length-prefixed JSON wire format via `kotlinx.serialization`. All message types implement the `GameMessage` sealed interface for compile-time type safety.

3. **File transfer:** `FileTransfer` uses a separate `ServerSocket` for binary video transfers with a 64KB buffer. Files are sent with an 8-byte size header and 32-byte SHA-256 checksum for integrity validation. Failed transfers retry automatically with exponential backoff (up to 3 attempts). Emits progress/success/failure events via Kotlin `Flow`.

### Key Data Flow

- Game master creates a game and starts a TCP server; other devices connect via Wi-Fi Direct
- Players authenticate via challenge-response: server sends a nonce, client replies with SHA-256(password + nonce)
- Game master broadcasts: `PlaybackCommand` (play/pause, next, previous), `PlaybackState` (position sync), `AdvancedCommand` (screen off, torch off), and video playlists
- Videos are transferred to player devices' local storage so playback works on intermittent connections
- Clients auto-reconnect on disconnect via `ReconnectionManager` (exponential backoff with jitter, max 10 retries)
- Game master periodically broadcasts a `GameStateSnapshot` so all devices can resume after a crash

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
4. Auto-generates release notes from commit messages since the last tag
5. Creates an annotated git tag with the release notes
6. Pushes the tag to origin, which triggers GitHub Actions

### Manual steps (equivalent)

```bash
git tag -a v1.0.0 -m "Initial release"
git push origin v1.0.0
```

### What happens on GitHub

The `.github/workflows/release.yml` workflow triggers on any `v*` tag push. It builds the debug APK in CI and creates a GitHub release with the APK attached as a downloadable asset.

To also build and attach a signed release APK, uncomment the release build lines in `.github/workflows/release.yml` (search for `SIGNING_TODO`) and configure the signing secrets in your GitHub repository settings. See [Setting Up Release Signing](#setting-up-release-signing).

## Setting Up Release Signing

The `build.gradle` signing config reads credentials from `local.properties` or environment variables. To enable signed release builds:

### 1. Generate a keystore

```bash
keytool -genkey -v -keystore release-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias project01
```

Store this file securely outside the repository. The `.gitignore` already excludes `*.jks` and `*.keystore`.

### 2. Configure local builds

Add to `project_01_android/local.properties` (already git-ignored):

```properties
RELEASE_STORE_FILE=/absolute/path/to/release-keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=project01
RELEASE_KEY_PASSWORD=your_key_password
```

You can now build locally with `assembleRelease`.

### 3. Configure CI (GitHub Actions)

Add these as repository secrets (Settings > Secrets and variables > Actions):

| Secret | Value |
|---|---|
| `RELEASE_STORE_FILE` | Absolute path to the keystore (or base64-encode it and decode in the workflow) |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | `project01` (or your chosen alias) |
| `RELEASE_KEY_PASSWORD` | Key password |

Then uncomment the release build lines in these files (search for `SIGNING_TODO`):

- **`release.sh`** — local release APK build
- **`.github/workflows/release.yml`** — CI release APK build and upload

## Roadmap

See `IMPLEMENTATION_PLAN.md` for the full improvement roadmap with detailed implementation plans for each priority.
