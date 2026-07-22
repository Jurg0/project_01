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

Single-activity MVVM architecture written entirely in Kotlin. `MainActivity` and `GameViewModel` were each split into focused collaborators (R1, R3, R4 on the roadmap); both are now thin coordinators.

### Layer Diagram

```
MainActivity (View — coordinator)
   ├── PermissionHelper          (ui/) runtime-permission gating
   ├── LightsAndScreenDelegate   (ui/) torch + screen brightness + black overlay
   ├── PlaybackViewDelegate      (ui/) ExoPlayer lifecycle + intent reconciliation
   └── GmControlsDelegate        (ui/) GM overlay + gestures + HID key mapping
       ↓
GameViewModel (ViewModel — LiveData facade + roster + snapshot/playlist glue)
   ├── PlaybackController        (session/) single source of truth for playback intent
   ├── FileTransferOrchestrator  (session/) file-transfer workflow + received-files set
   └── SessionController         (session/) game lifecycle + password handshake + role bit
       ↓
GameRepository (Repository — Android system services, Wi-Fi P2P, broadcast receiver)
       ↓
GameSync (Facade over NetworkManager) + FileTransfer (separate ServerSocket)
       ↓
SocketNetworkManager (TCP sockets, implements NetworkManager interface)
```

### Networking Stack

Three layers handle connectivity:

1. **Wi-Fi Direct (P2P layer):** `p2p/ConnectionService.kt` and `p2p/WifiDirectBroadcastReceiver.kt` manage device discovery and P2P connections using Android's WifiP2pManager API. ConnectionService is a foreground service with a wake lock and Wi-Fi lock to maintain the connection through screen-off and doze mode.

2. **Game state sync (Session layer):** `GameSync` wraps `NetworkManager` (interface) with `SocketNetworkManager` as the TCP socket implementation. Server listens on port 8888. Uses kotlinx.serialization JSON with a 4-byte length-prefixed wire format (`MessageEnvelope` for encode/decode). All network messages implement the `GameMessage` sealed interface. `MessageEnvelope.PROTOCOL_VERSION` is sent in the `PasswordChallenge` handshake to detect version mismatches between devices. Broadcasts messages to all connected clients via a `clients: Map<String, OutputStream>`.

3. **File transfer:** `FileTransfer` uses a separate `ServerSocket` for binary video transfers with a 64KB buffer. Files are sent with an 8-byte size header and 32-byte SHA-256 checksum for integrity validation. Failed transfers retry automatically with exponential backoff (up to 3 attempts). Emits progress/success/failure events via Flow.

### Playback Single Source of Truth (R1)

`PlaybackController` owns `intent: StateFlow<PlaybackIntent>` — the *commanded* `(videoIndex, positionMs, isPlaying)` triple. ExoPlayer on both GM and player devices is reconciled downstream from intent; the wire (`PlaybackCommand`) carries the same intent. There is no other source of truth.

- GM mutators: `play()`, `pause()`, `previous(playlistSize)`, `advanceOrResume(playlistSize, atEndOfCurrent)` update intent **and** broadcast `PlaybackCommand`.
- Player-side: `applyFromWire(command)` updates intent without re-broadcasting.
- Player.Listener feedback: `onPlayerTransition(index, position, isPlaying)` only re-broadcasts when it disagrees with intent (catches natural end-of-video pauses from `pauseAtEndOfMediaItems`). A 500ms `COMMAND_GRACE_MS` window after an explicit mutator suppresses the listener-driven re-broadcast race.
- **End-of-video is detected via `STATE_ENDED`, not `playWhenReady`:** `pauseAtEndOfMediaItems` parks ExoPlayer in `STATE_ENDED` with `playWhenReady` still true, so `onIsPlayingChanged`/`playWhenReady` can't see the end. `PlaybackViewDelegate.onPlaybackStateChanged` reports `STATE_ENDED` as a pause (`isPlaying=false`) so intent flips to paused. Without it the intent stayed "playing" and the next Play press mis-branched in `advanceOrResume` (advance-and-pause instead of advance-and-play) — the next video never started (field bug).
- **Player is a pure follower:** only the **GM's** `onPlayerTransition` drives intent; on a player device it returns early (`if (!isGameMaster()) return`) and never mutates intent — player intent comes only from the wire (`applyFromWire` / `applyDriftCorrection`). Letting a player's listener touch intent stranded it on blue: a transient "paused" callback during a seek/buffer out of a parked item flipped intent to paused, and the never-start-playback guard below then blocked the recovering "playing" callback, so GM playback never appeared on the player (field-observed).
- **Listener invariant (GM):** the GM's `onPlayerTransition` may report a *pause* (natural end-of-video) but must never *start* playback — a listener `isPlaying=true` while intent is paused is always a transient reconciler seek artifact and is ignored (`if (isPlaying && !current.isPlaying) return`). Without this, a Play/Next-while-playing press (advance-and-pause on blue) could flip the GM's intent back to playing outside its grace window while players stayed on the blue safe-screen — a GM/player desync seen in field testing.
- `PlaybackState` on the wire is **position-only drift correction**, never intent.
- `PlaybackViewDelegate` collects `intent` in a `repeatOnLifecycle(STARTED)` coroutine and reconciles ExoPlayer (seek + `playWhenReady` + surface visibility). Single code path drives ExoPlayer from any source.

### Key Data Flow

- Game master creates a game → starts TCP server → other devices connect via Wi-Fi Direct (`SessionController.createGame` → `handleConnectionInfo`).
- Players join with a password via challenge-response: server sends `PasswordChallenge(nonce)`, client replies with `PasswordMessage(SHA-256(password + nonce))`, server verifies and sends `PasswordResponseMessage(success)` followed by `pushInitialStateTo` (`VideoListMessage` + `PlaybackCommand`).
- Game master broadcasts: `PlaybackCommand` (PLAY_PAUSE, NEXT, PREVIOUS), `PlaybackState` (position-only drift sync, every 5s when playing), `AdvancedCommand` (TURN_OFF_SCREEN, DEACTIVATE_TORCH, LIGHTS_ON, LIGHTS_OFF), video playlists as `VideoListMessage`.
- Clients auto-reconnect on disconnect via `ReconnectionManager` (exponential backoff with jitter, max 10 retries). `SessionController` observes the reconnect state flow and updates `connectionState` LiveData.
- Videos are transferred to player devices' local storage via `FileTransfer` (orchestrated by `FileTransferOrchestrator`) so playback works on slow/intermittent connections. `handleVideoList` resolves each title against `filesDir` first; only missing files trigger a transfer.
- Game master periodically broadcasts a `GameStateSnapshot` so all devices can resume after a crash.
- Protocol version is checked during the `PasswordChallenge` handshake; mismatched versions show a `UiError.Critical` to the user.

### Reactive Patterns

- `PlaybackController.intent` is a `StateFlow<PlaybackIntent>`; collected by `PlaybackViewDelegate`.
- `SocketNetworkManager.events` and `FileTransfer` emit events via Kotlin `Flow`.
- `NetworkEvent` is a sealed class for typed network events.
- `ConnectionStatus` and `PasswordVerified` LiveDatas live on `SessionController` and are proxied through `GameViewModel`.
- ViewModel exposes other state to the View via `LiveData` (videos, players, isGameStarted, uiError, advancedCommand).
- Coroutines with `SupervisorJob` used throughout the networking layer.

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
- Controllers/orchestrators live in `session/`; View-side delegates live in `ui/`.
- All network message types implement `GameMessage` sealed interface with `@Serializable` annotations; `classDiscriminator = "msg_type"` (not "type" — clashes with PlaybackCommand's `type` field)
- `VideoDto` bridges `Video` (uses Android `Uri`) to serializable form; extension functions `Video.toDto()` / `VideoDto.toVideo()` in Video.kt
- Data classes use `@Parcelize` (Player, Video)
- `GameRepository` constructs dependencies directly (no DI framework): `GameSync(SocketNetworkManager())`, `FileTransfer()`, plus `SnapshotManager` and `PlaylistStore`. All four are constructor-injectable for tests.
- `GameViewModel` takes `GameRepository` as a default constructor parameter and constructs `PlaybackController` / `FileTransferOrchestrator` / `SessionController` in property initializers. Controllers receive narrow callbacks (e.g. `isGameMaster: () -> Boolean`, `videosProvider: () -> List<Video>?`) rather than the full `GameViewModel`.
- Cross-controller hooks: `SessionController` fires `onSessionStarted(isHost)` / `onSessionEnded(remoteInitiated)` callbacks so `GameViewModel` can start/stop periodic jobs and clear session-specific state. `handleClientDisconnected()` returns a Boolean indicating whether the caller should still handle roster cleanup (true for game master, false for player).
- `MainActivity` delegates own the launcher (`PermissionHelper`), the camera/screen hardware (`LightsAndScreenDelegate`), the ExoPlayer (`PlaybackViewDelegate`), and the GM controls (`GmControlsDelegate`). The activity is a coordinator; `dispatchKeyEvent` is a one-line shim that forwards to `GmControlsDelegate`.
- `TestNetworkManager` in the test directory provides a mock `NetworkManager` for unit tests
- `PasswordHasher` object handles nonce generation (`SecureRandom`) and SHA-256 hashing
- `ReconnectionManager` uses `StateFlow<ReconnectionState>` observed by `SessionController` via `collectLatest`
- App version derived from git tags at build time (`build.gradle`); `versionName` from latest `v*` tag, `versionCode` from tag count
- Permissions are gated in `MainActivity` via `PermissionHelper.requirePermissions(...)` before any Wi-Fi P2P API call; the actual API calls retain `@SuppressLint("MissingPermission")` since the permission check is at the View boundary, not at the call site.

## Tests

- 176 unit tests across the codebase as of R4.
- `PlaybackControllerTest` (22), `FileTransferOrchestratorTest` (10), `SessionControllerTest` (23) cover the extracted controllers without Android view bindings.
- `GameViewModelTest` covers the remaining roster/snapshot/playlist glue and routes `NetworkEvent` events through the dispatcher to verify controller wiring.
- `MainActivityTest` is a Robolectric `ActivityScenario` smoke test that catches construction-time wiring bugs (delegate init order, ViewModel factory failures, etc.).
- `MainActivity` UI delegates are mostly thin view wiring. The exception now covered: `GmControlsDelegate`'s presenter HID key → GM action mapping is extracted into the pure `presenterActionFor(keyCode, ctrlPressed)` and tested in `GmControlsKeyMappingTest` (plain JVM, no Robolectric). `PermissionHelper` and `LightsAndScreenDelegate` still have testable logic that could be covered later if regressions appear.
- Bluetooth presenter mapping is field-verified against a **Norwii N21 BLE**: page back/forward = DPAD_LEFT/RIGHT (default arrow mode) → Prev/Next; "Mark" button = Ctrl+P → toggle Light; Volume +/- deliberately unmapped. Capture new devices' keycodes with `adb logcat` (the app had a temporary `PresenterKeys` diagnostic log for this, since removed).

## Releases

- Tag-based releases: `./release.sh v1.0.0 "Optional message"` creates a git tag and pushes it
- GitHub Actions workflow (`.github/workflows/release.yml`) builds the APK and creates a GitHub release on tag push
- Release notes auto-generated from commit messages since the last tag

## Release Signing

The `build.gradle` signing config reads from `local.properties` or environment variables. Release builds use R8 code shrinking (`minifyEnabled true`, `shrinkResources true`) with ProGuard rules in `app/proguard-rules.pro`. See `README.md` for full setup instructions. Files with `SIGNING_TODO` comments (`release.sh`, `.github/workflows/release.yml`) need uncommenting once the keystore and CI secrets are configured.

## Known Technical Debt

See `IMPLEMENTATION_PLAN.md` for the full improvement roadmap. The R1–R8 refactor is complete; only R9 (this file) was the doc sweep.

Two pre-existing minor issues surfaced during the R3/R4 review are now fixed:

- `LightsAndScreenDelegate.resetToLobbyDefaults` now calls `setTorchMode(false)` so the hardware flashlight is actually turned off on session end, not just the flag/labels.
- `SessionController` resets `_passwordVerified` to `true` in both teardown paths (`handleEndGame` / `endGame`), so a cached `false` from a prior failed join can no longer re-fire the "Incorrect password" toast on activity recreation. Covered by two regression tests in `SessionControllerTest`.
