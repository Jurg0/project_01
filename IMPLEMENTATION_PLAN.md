# Refactor Roadmap

Forward-looking refactor work. Ranked by reliability impact (top) then hygiene (bottom). Status markers: `○` open, `◐` in progress, `●` done.

The recent round of playback / sync bugs (drift filter dropping commands, GM/player index disagreement, white pulsing, etc.) shared a common root: too many parallel state representations and too much logic concentrated in `MainActivity` + `GameViewModel`. This list is the recovery plan.

> **Session-handover note:** Items R2, R5, R6, R7, R8 are landed. Items R1, R3, R4, R9 are open. Each open item below carries enough context — files, line ranges, design decisions already made, test guidance — to resume cold in a fresh session.

---

## Reliability (highest impact)

### ○ R1 — Single source of truth for playback state

**Problem.** The GM keeps three parallel views of playback state:

- ExoPlayer (`player.currentMediaItemIndex`, `player.currentPosition`, `player.playWhenReady`)
- `GameViewModel` fields: `currentVideoIndex`, `currentPlaybackPosition`, `currentIsPlaying` (declared at `GameViewModel.kt:59-61`)
- The wire (`PlaybackState` for periodic drift sync, `PlaybackCommand` for explicit actions)

Every recent sync bug was these three disagreeing:

- White pulsing — periodic `PlaybackState` re-emitted `_showVideo=true` even when nothing changed.
- Previous-button desync — `seekToPreviousMediaItem()` interacted unpredictably with `pauseAtEndOfMediaItems`; the broadcast captured the index *after* ExoPlayer's async state transition.
- Video-shows-but-paused — `applyPlaybackState`'s drift filter dropped `PlaybackCommand` emission when drift was small but `playWhenReady` had transitioned.
- Two broadcasts per button press — the `Player.Listener` and the explicit handler both broadcast; order on the wire is non-deterministic (R2).

The right model: **ViewModel owns the intended state. ExoPlayer (on both GM and player) is driven from it. The wire carries the intended state. The Player.Listener only feeds a position counter for periodic drift correction — never the index, never the play/pause intent.**

**Proposed design.**

Create a new class `PlaybackController` (location: `app/src/main/java/com/project01/session/PlaybackController.kt` or under `viewmodel/`):

```kotlin
data class PlaybackIntent(
    val videoIndex: Int,
    val positionMs: Long,
    val isPlaying: Boolean,
)

class PlaybackController(
    private val gameSync: GameSync,
    private val scope: CoroutineScope,
) {
    private val _intent = MutableStateFlow(PlaybackIntent(0, 0L, false))
    val intent: StateFlow<PlaybackIntent> = _intent

    // GM-side mutators. Each updates _intent and broadcasts a PlaybackCommand.
    fun play(index: Int, positionMs: Long = 0L)
    fun pause()
    fun previous(playlistSize: Int)  // computes target index, calls play(...)
    fun advance(playlistSize: Int)   // pause + seek to next item start

    // Player-side: called by GameViewModel when a PlaybackCommand arrives.
    fun applyFromWire(command: PlaybackCommand)

    // Periodic drift sync (GM only). Reads ExoPlayer's actual position and
    // broadcasts a PlaybackState containing only the position; client uses
    // only for drift correction, never to change index or playing/paused.
    fun broadcastDriftSync(currentExoPosition: Long)
}
```

**Migration plan (suggested order):**

1. Create `PlaybackController` with the mutators. Initially have the mutators *also* update the old `GameViewModel.currentVideoIndex/Position/IsPlaying` fields (mirror), so nothing else breaks yet.
2. Move the explicit broadcast logic out of `GameViewModel.commandPlayback` (currently at `GameViewModel.kt:625-637`) into `PlaybackController.play/pause`. Make `commandPlayback` call into the controller.
3. Replace `MainActivity.onGmPrevious` / `onGmPlayNext` (currently at `MainActivity.kt:191-238`) to call `controller.previous(...)` / `controller.advance(...)` / `controller.play(...)` instead of touching ExoPlayer directly. ExoPlayer becomes a downstream observer of `controller.intent`.
4. In `MainActivity.initializePlayer` (around `MainActivity.kt:716`), add a coroutine that collects `controller.intent` and reconciles ExoPlayer state (seek + playWhenReady) only when intent differs from ExoPlayer's current state.
5. In `GameViewModel.handleGameSyncEvent`'s `PlaybackCommand` branch (currently posts to `_playbackCommand` LiveData at line ~190), route it through `controller.applyFromWire(...)`. The MainActivity coroutine in step 4 then drives ExoPlayer for both received-from-wire and locally-initiated changes — single code path.
6. Drop the old fields and the `_playbackCommand` LiveData once nothing reads them.
7. Periodic sync (currently `GameViewModel.startPeriodicPlaybackSync` at `GameViewModel.kt:158-170`) moves to `PlaybackController.broadcastDriftSync` and takes ExoPlayer's actual position rather than the cached field.

**Tests:**
- New `PlaybackControllerTest` — unit tests for `play/pause/previous/advance` mutators, intent updates, broadcast envelope contents.
- Existing `GameViewModelTest`: the playback-related tests collapse considerably. Many can move to `PlaybackControllerTest`.

**Acceptance:**
- Pressing Prev/Play/Next on GM updates `controller.intent` *and* broadcasts a single `PlaybackCommand`. ExoPlayer state reconciles from intent — no direct ExoPlayer calls in button handlers.
- Player receiving `PlaybackCommand` routes through the same controller; same reconciliation path drives its ExoPlayer.
- Manual test: rapid Prev/Next/Light presses don't produce mid-state visual artifacts on either device.

**Estimated scope:** multi-hour. Better as its own focused session. Commit incrementally so partial state can be reverted.

---

### ● R2 — Stop listener-driven re-broadcasts (race-guard version)

**Done via a grace-window guard rather than a full architectural pull-apart.** `GameViewModel.broadcastPlaybackState` now suppresses its broadcast when called within `COMMAND_GRACE_MS` (500ms) of an explicit `commandPlayback` — eliminating the double-broadcast race on every GM button press. When the listener *does* broadcast (natural transitions like `pauseAtEndOfMediaItems` end-of-video, no recent explicit command), it now emits `PlaybackCommand` instead of `PlaybackState`, so the receiver's drift filter can't drop the pause.

`commandPlayback` stamps `lastCommandPlaybackAtMs`; the listener path reads it. No other call sites change.

**Limitation left for R1:** the listener still has authority over end-of-video pauses. Under R1 this moves into `PlaybackController` and the listener becomes a pure position feed.

**Files modified:** `GameViewModel.kt` only (~25 lines + companion constant).

---

## Code health (medium impact)

### ○ R3 — Split `GameViewModel`

**Problem.** ~750 lines mixing: networking handshake, password verification, file-transfer orchestration, periodic playback sync, snapshot save/restore, playlist persistence, Bluetooth presence, GM/player role tracking. Every change risks unrelated side-effects.

**Depends on:** R1 (PlaybackController is the first extraction).

**Real extraction candidates:**

- `SessionController` — `handleConnectionInfo`, `handlePasswordChallenge`, `handlePasswordMessage`, `pushInitialStateTo`, `createGame`, `joinGame`, `endGame`, `handleEndGame` reconnection plumbing.
- `PlaybackController` — see R1.
- `FileTransferOrchestrator` — `requestFileTransfer`, `handleFileTransferRequest`, `onFileTransferSuccess`, `handleVideoList`'s file-resolve logic, `receivedVideoFiles` set.
- `GameViewModel` keeps: LiveData exposure for the View, wiring (observers + lifecycle), Bluetooth presence check, snapshot orchestration glue, periodic status broadcast.

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
| R1   | ○      | —          | multi-hour  |
| R3   | ○      | R1         | several hours, splittable |
| R4   | ○      | R1, R3     | several hours, splittable |
| R9   | ○      | R1–R4      | small sweep |

---

## Process

- One item per commit (or per small batch of related items).
- Update this file as items move `○ → ◐ → ●`. Replace the open-item block with a short summary of what landed and which files moved.
- For items with non-obvious design choices, append a short "**Decision:**" note inline.
- When all reliability items (R1, R2) are `●`, do a fresh field test before tackling the structural splits (R3, R4).
- **Commit hygiene:** the recent commit log (`737f896`, `3475212`, `3816a31`, `ea834bc`, `4292fbc`) shows the cadence — one focused change per commit with a short summary line.
