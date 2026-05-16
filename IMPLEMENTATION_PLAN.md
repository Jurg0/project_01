# Refactor Roadmap

Forward-looking refactor work. Ranked by reliability impact (top) then hygiene (bottom). Status markers: `○` open, `◐` in progress, `●` done.

The recent round of playback / sync bugs (drift filter dropping commands, GM/player index disagreement, white pulsing, etc.) shared a common root: too many parallel state representations and too much logic concentrated in `MainActivity` + `GameViewModel`. This list is the recovery plan.

> **Session-handover note:** Items R1, R2, R5, R6, R7, R8 are landed. Items R3, R4, R9 are open. Each open item below carries enough context — files, line ranges, design decisions already made, test guidance — to resume cold in a fresh session.

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

### ◐ R3 — Split `GameViewModel`

Landing one extraction per commit. Current status:

- `PlaybackController` — landed in R1.
- `FileTransferOrchestrator` — **landed.** `requestFileTransfer`, `handleFileTransferRequest`, `onFileTransferSuccess`, the `handleVideoList` file-resolve loop, and the `receivedVideoFiles` set all moved to `session/FileTransferOrchestrator.kt`. `GameViewModel.handleVideoList` is now a 10-line shim. 10 focused unit tests in `FileTransferOrchestratorTest`.
- `SessionController` — open. Should own `handleConnectionInfo`, `handlePasswordChallenge`, `handlePasswordMessage`, `pushInitialStateTo`, `createGame`, `joinGame`, `endGame`, `handleEndGame`, plus the `gamePassword`/`pendingPassword`/`pendingNonce`/`localPlayerName`/`player` (role-tracking) state and the reconnect host/port memory. After this lands, `GameViewModel` keeps: LiveData exposure, observer wiring, Bluetooth presence, snapshot orchestration glue, periodic status broadcast, playlist editing.

**Migration approach:** extract one at a time, keep `GameViewModel` as a facade that delegates to the new classes. Move one method at a time, run tests after each.

**Tests:** the extracted classes get focused unit tests; `GameViewModelTest` shrinks correspondingly.

**Estimated scope:** several hours. Splittable across multiple sessions per-class.

### ○ R4 — Split `MainActivity`

**Problem.** ~800 lines doing permissions, gestures, ExoPlayer wiring, key-event mapping, error display, screen/torch hardware, dialogs, lifecycle, accessibility.

**Depends on:** R1 (cleaner ExoPlayer wiring) and ideally R3 (cleaner delegation surface).

**Candidates:**

- `GmControlsDelegate` — gesture detector (`MainActivity.kt:172-186`), `onGm*` handlers (lines 191-238), GM overlay visibility, `dispatchKeyEvent` HID mapping (lines 248-283).
- `PlaybackViewDelegate` — `initializePlayer`/`releasePlayer`, playlist updates (`updatePlayerPlaylist`), surface visibility observer.
- `LightsAndScreenDelegate` — `setTorchMode`, `setScreenBrightness`, `applyScreenOn/Off`, `applyTorchOn/Off`, black overlay management.
- `PermissionHelper` — `requirePermissions`, `wifiP2pPermissions`, `hasPermissions`, `permissionLauncher`.

**Decision left for the doing:** delegates vs. fragments. Delegates (plain Kotlin classes constructed in `onCreate`, holding a reference to `binding` + `gameViewModel`) are simpler and avoid Fragment lifecycle complexity. Recommend delegates.

**Estimated scope:** several hours. Lower priority than R3 — `MainActivity` is busy but mostly readable; the bugs lived in `GameViewModel`.

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

### ○ R9 — Refresh top-of-file architecture summary in `CLAUDE.md` if it's drifted

Verify after R1–R4 land that the layer diagram and conventions in `CLAUDE.md` still match reality. Probable updates: `PlaybackController` joins the architecture diagram, the various `MainActivity` delegates get a one-line mention, the "GameViewModel takes GameRepository as a default constructor parameter" convention may need updating if the split changes it.

---

## Open-item quick-glance

| Item | Status | Depends on | Rough scope |
|------|--------|------------|-------------|
| R3   | ◐      | R1         | several hours, splittable (FileTransferOrchestrator landed; SessionController open) |
| R4   | ○      | R1, R3     | several hours, splittable |
| R9   | ○      | R1–R4      | small sweep |

---

## Process

- One item per commit (or per small batch of related items).
- Update this file as items move `○ → ◐ → ●`. Replace the open-item block with a short summary of what landed and which files moved.
- For items with non-obvious design choices, append a short "**Decision:**" note inline.
- When all reliability items (R1, R2) are `●`, do a fresh field test before tackling the structural splits (R3, R4).
- **Commit hygiene:** the recent commit log (`737f896`, `3475212`, `3816a31`, `ea834bc`, `4292fbc`) shows the cadence — one focused change per commit with a short summary line.
