# Refactor Roadmap

Forward-looking refactor work. Ranked by reliability impact (top) then hygiene (bottom). Status markers: `○` open, `◐` in progress, `●` done.

The recent round of playback / sync bugs (drift filter dropping commands, GM/player index disagreement, white pulsing, etc.) shared a common root: too many parallel state representations and too much logic concentrated in `MainActivity` + `GameViewModel`. This list is the recovery plan.

> **Session-handover note:** All items R1–R9 are landed. The refactor roadmap is closed; this file is a historical record. Subsequent reliability or code-health work should start a fresh section or a new file.

---

## Reliability (highest impact)

### ● R1 — Single source of truth for playback state

Extracted into `session/PlaybackController.kt`. `PlaybackIntent(videoIndex, positionMs, isPlaying)` is the single source of truth, exposed as a `StateFlow`. GM-side mutators (`play`, `pause`, `previous`, `advanceOrResume`) update intent and broadcast `PlaybackCommand`. Player-side `applyFromWire` updates intent without re-broadcasting. `applyDriftCorrection` handles incoming `PlaybackState` purely for position correction; falls back to `applyFromWire` if state disagrees with intent (defensive — shouldn't happen in normal operation).

`MainActivity.initializePlayer` now runs a `repeatOnLifecycle(STARTED)` coroutine that collects `playbackController.intent` and reconciles ExoPlayer (seek when index or position diverges, set `playWhenReady`, toggle surface visibility). The ExoPlayer `Player.Listener` feeds `controller.onPlayerTransition(index, position, isPlaying)`, which only updates intent + broadcasts when ExoPlayer's state disagrees with intent and we're outside the R2 grace window — natural end-of-video pauses still propagate.

Deleted from `GameViewModel`: the `currentVideoIndex/Position/IsPlaying` fields, `commandPlayback`, `broadcastPlaybackState`, `applyPlaybackState`, the `_playbackCommand` and `_showVideo` LiveDatas, and the `PLAYBACK_SYNC_INTERVAL_MS`/`PLAYBACK_DRIFT_THRESHOLD_MS`/`COMMAND_GRACE_MS` companion constants (now on `PlaybackController`). `MainActivity.handlePlaybackCommand` and its `playbackCommand`/`showVideo` observers are gone — the reconciliation coroutine is the only path that touches ExoPlayer.

**Decision:** `pause()` writes `lastObservedPositionMs` into the broadcast intent so clients reconcile to the GM's actual paused position, not the stale commanded one. `advanceOrResume(playlistSize, atEndOfCurrent)` takes the `atEnd` flag from MainActivity (ExoPlayer is the only authority on item duration) — the controller does not track ExoPlayer-only state.

**Files modified:** `session/PlaybackController.kt` (new), `viewmodel/GameViewModel.kt`, `MainActivity.kt`, `session/PlaybackControllerTest.kt` (new, 22 tests), `viewmodel/GameViewModelTest.kt` (3 tests rewritten against intent instead of LiveData; the `broadcastPlaybackState` test removed). 141 unit tests pass.

---

### ● R2 — Stop listener-driven re-broadcasts (race-guard version)

**Done via a grace-window guard rather than a full architectural pull-apart.** `GameViewModel.broadcastPlaybackState` now suppresses its broadcast when called within `COMMAND_GRACE_MS` (500ms) of an explicit `commandPlayback` — eliminating the double-broadcast race on every GM button press. When the listener *does* broadcast (natural transitions like `pauseAtEndOfMediaItems` end-of-video, no recent explicit command), it now emits `PlaybackCommand` instead of `PlaybackState`, so the receiver's drift filter can't drop the pause.

`commandPlayback` stamps `lastCommandPlaybackAtMs`; the listener path reads it. No other call sites change.

**Limitation left for R1:** the listener still has authority over end-of-video pauses. Under R1 this moves into `PlaybackController` and the listener becomes a pure position feed.

**Files modified:** `GameViewModel.kt` only (~25 lines + companion constant).

---

## Code health (medium impact)

### ● R3 — Split `GameViewModel`

Done across three commits:

- **R1** — `PlaybackController` (single source of truth for playback state).
- **R3a** — `FileTransferOrchestrator` owns `requestFileTransfer`, `handleFileTransferRequest`, `onFileTransferSuccess`, the `handleVideoList` file-resolve loop, and the `receivedVideoFiles` set. `GameViewModel.handleVideoList` shrank to a 10-line shim. 10 focused tests in `FileTransferOrchestratorTest`.
- **R3b** — `SessionController` owns `handleConnectionInfo`, the password handshake (`handlePasswordChallenge` / `handlePasswordMessage` / `handlePasswordResponseMessage`), `pushInitialStateTo`, `createGame`, `connectToPlayer`, `joinGame`, `endGame`, `handleEndGame`, `retryConnection`, the role-tracking `player` field, and the reconnect host/port memory. `connectionState` and `passwordVerified` LiveData moved with it; `GameViewModel` re-exposes them as proxies. Communication with `GameViewModel` is via `onSessionStarted(isHost)` and `onSessionEnded(remoteInitiated)` callbacks plus `handleClientDisconnected()` returning a boolean for GM-side roster handling. 23 focused tests in `SessionControllerTest`.

**Decision:** `GameViewModel` stays as the View-facing facade. It still owns: LiveData proxying, player-roster management (PlayerNameMessage / PlayerStatusMessage handlers + add/remove on Client(Dis)Connected for GM), Bluetooth presence prompt, snapshot orchestration glue, periodic status broadcast, named playlist + playlist editing, advanced commands (screen/torch/lights), and `NetworkEvent.Error` → `UiError.Recoverable`. Net result: `GameViewModel` is 380 lines (down from ~750); the heavy logic now lives in four focused classes (PlaybackController, FileTransferOrchestrator, SessionController, GameRepository).

### ● R4 — Split `MainActivity`

Done as four delegate extractions in one commit. `MainActivity` dropped from ~730 to 441 lines.

- `PermissionHelper` (`ui/PermissionHelper.kt`) — `hasPermissions`, `requirePermissions`, `wifiP2pPermissions`, plus `onPermissionsResult`. The `ActivityResultLauncher` itself still lives on the Activity (Android requires registration before STARTED); the helper takes a `launchPermissions: (Array<String>) -> Unit` callback.
- `LightsAndScreenDelegate` (`ui/LightsAndScreenDelegate.kt`) — owns `isScreenOff` / `isTorchOn`, applies all six `AdvancedCommandType` variants, manages torch (CameraManager), screen brightness, the black overlay, and the lobby-button labels. Exposes `isLightsOff()` so the GM "light" toggle has the right sign and `resetToLobbyDefaults()` for `showLobby`.
- `PlaybackViewDelegate` (`ui/PlaybackViewDelegate.kt`) — owns the ExoPlayer lifecycle, the listener that feeds `playbackController.onPlayerTransition`, the `repeatOnLifecycle(STARTED)` intent-reconciliation coroutine, and `updatePlaylist`. Exposes `mediaItemCount()` and `isAtEndOfCurrent()` for GM control code (ExoPlayer is the only authority on item duration).
- `GmControlsDelegate` (`ui/GmControlsDelegate.kt`) — gesture detector, the four GM buttons, GM overlay visibility, and the bluetooth-presenter HID key mapping (`dispatchKeyEvent`). Takes the previous two delegates plus `isGameMaster`/`isGameStarted` accessors and a long-press callback (the End Game dialog still lives on the Activity).

**Decision:** plain delegates over Fragments (no Fragment lifecycle complexity; just plain Kotlin classes constructed in `onCreate` that hold a binding/activity reference). `MainActivity.dispatchKeyEvent` is a one-line shim that returns true when the delegate consumed the event, else falls through to super.

**Files modified:** `MainActivity.kt`, `ui/PermissionHelper.kt` (new), `ui/LightsAndScreenDelegate.kt` (new), `ui/PlaybackViewDelegate.kt` (new), `ui/GmControlsDelegate.kt` (new). 176 unit tests pass; APK builds clean. No new tests — the delegates are mostly thin View wiring + glue, and `MainActivityTest` (Robolectric `ActivityScenario` smoke test) still validates the full construction path.

### ● R5 — Constructor-inject `GameRepository`'s collaborators

`GameRepository` now accepts `gameSync`, `fileTransfer`, `snapshotManager`, `playlistStore` as default-argument constructor parameters (no DI framework). Production callers are unchanged. New tests can inject fakes via the constructor instead of mocking individual getters. **Follow-up:** the existing `GameViewModelTest` still mocks the whole repository; converting it to use a real `GameRepository` with mocked collaborators is a separate item if/when the test setup gets painful again.

---

## Hygiene (low impact, quick wins)

### ● R6 — Rename `VideoAdapter.isGameMaster` → `editable`

The flag was overloaded: in the lobby it meant "show edit controls"; during a session it's forced `false` regardless of role. The name was misleading. Renamed in `VideoAdapter.kt` (field, ctor param, `bind()` param, condition) and updated the two assignments in `MainActivity.showLobby` / `showGame`. No test references — done.

### ● R7 — `handleVideoList` skips files already received

`GameViewModel.handleVideoList` now resolves each incoming title against `filesDir` *before* deciding whether to request a transfer. If the file already exists locally, the playlist entry's URI is rewritten to `Uri.fromFile(...)` on the spot and `receivedVideoFiles` is populated. Saves a roundtrip per video on every GM re-broadcast (new joiners, named-playlist load) and also recovers playlist URIs after a process restart where files survived but the in-memory set didn't.

### ● R8 — Purge stale milestone references in comments

Cleaned `M5.2` / `M5.1` / `M3` references from `activity_main.xml` (2 lines), `MainActivity.kt` (HID kdoc) and `GameViewModel.kt` (named-playlist section heading). No `Priority N` style references remained.

### ● R9 — Refresh architecture summary in `CLAUDE.md`

`CLAUDE.md` updated: new layer diagram shows the four delegates under `MainActivity` and the three controllers under `GameViewModel`. Added a "Playback Single Source of Truth (R1)" section documenting the intent flow, the listener-feedback grace window, and the position-only role of `PlaybackState` on the wire. Conventions updated: controller construction pattern, narrow callback injection, `dispatchKeyEvent` shim, `PermissionHelper`-gated Wi-Fi APIs. Added a "Tests" subsection summarizing the 176-test suite and the deliberately-uncovered View delegates. Known Technical Debt section now lists only the two pre-existing minor bugs flagged in the R3/R4 review (torch reset on lobby; `_passwordVerified` reset on session end).

---

## Status

All roadmap items (R1–R9) are landed. The recommended next step is a fresh field test of R1+R3+R4 together — that batch of refactors touched ExoPlayer wiring, the session lifecycle, and the entire `MainActivity` surface, so field validation is overdue.
