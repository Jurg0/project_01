# Refactor Roadmap

Forward-looking refactor work. Ranked by reliability impact (top) then hygiene (bottom). Status markers: `○` open, `◐` in progress, `●` done.

The recent round of playback / sync bugs (drift filter dropping commands, GM/player index disagreement, white pulsing, etc.) shared a common root: too many parallel state representations and too much logic concentrated in `MainActivity` + `GameViewModel`. This list is the recovery plan.

---

## Reliability (highest impact)

### ○ R1 — Single source of truth for playback state

The GM keeps three parallel views of playback state:

- ExoPlayer (`player.currentMediaItemIndex`, `player.currentPosition`, `player.playWhenReady`)
- `GameViewModel.currentVideoIndex` / `currentPlaybackPosition` / `currentIsPlaying`
- The wire (`PlaybackState` for periodic sync, `PlaybackCommand` for explicit actions)

Every recent sync bug was these three disagreeing. The right model is: ViewModel owns the intended state; ExoPlayer (on both GM and player) is **driven** from it; the wire carries the intended state. The `Player.Listener` only updates the position drift counter — never the index, never the play/pause intent.

**Target:**
- `PlaybackController` (new) holds `state: StateFlow<PlaybackIntent>` where `PlaybackIntent = (videoIndex, position, isPlaying)`
- GM calls `controller.play(index, position)` / `controller.pause()` / `controller.advance()` / `controller.previous()`. The controller updates state and broadcasts `PlaybackCommand`.
- Player applies received `PlaybackCommand` by calling the same controller mutators.
- `MainActivity` observes `state` and drives ExoPlayer (no direct `player.seekTo(...)` from button handlers).
- Periodic drift sync stays, but only corrects position — never the index or play/pause.

### ○ R2 — Stop listener-driven re-broadcasts

`Player.Listener.onIsPlayingChanged` currently calls `broadcastPlaybackState`, racing with the explicit `commandPlayback` on every GM button press (two messages, sometimes with mismatched mid-seek indices). Once R1 lands, this listener should *only* update a local position-drift counter for the periodic sync. No broadcasts from the listener.

---

## Code health (medium impact)

### ○ R3 — Split `GameViewModel`

Currently mixes: networking handshake, password verification, file-transfer orchestration, periodic playback sync, snapshot save/restore, playlist persistence, Bluetooth presence, GM/player role tracking. Real extraction candidates:

- `SessionController` — connection lifecycle, password handshake, `pushInitialStateTo`
- `PlaybackController` — see R1
- `FileTransferOrchestrator` — request/serve files, URI swap, `receivedVideoFiles`
- `GameViewModel` keeps wiring + LiveData exposure for the View

### ○ R4 — Split `MainActivity`

~800 lines doing permissions, gestures, ExoPlayer wiring, key-event mapping, error display, screen/torch hardware, dialogs, lifecycle. Candidates:

- `GmControlsDelegate` — gesture detector, `onGm*` handlers, GM overlay visibility, HID key mapping
- `PlaybackViewDelegate` — ExoPlayer init/release, playlist updates, surface visibility
- `LightsAndScreenDelegate` — `setTorchMode`, `setScreenBrightness`, black overlay
- `PermissionHelper` — `requirePermissions`, `wifiP2pPermissions`

### ○ R5 — Constructor-inject `GameRepository`'s collaborators

`GameRepository` instantiates `GameSync`, `FileTransfer`, `SnapshotManager`, `PlaylistStore` directly. Tests mock 12+ getters by hand and break whenever the constructor changes. Move collaborator construction to a default-argument list (`GameRepository(application, gameSync = …, fileTransfer = …, …)`). No DI framework needed — just default arguments.

---

## Hygiene (low impact, quick wins)

### ● R6 — Rename `VideoAdapter.isGameMaster` → `editable`

The flag was overloaded: in the lobby it meant "show edit controls"; during a session it's forced `false` regardless of role. The name was misleading. Renamed in `VideoAdapter.kt` (field, ctor param, `bind()` param, condition) and updated the two assignments in `MainActivity.showLobby` / `showGame`. No test references — done.

### ● R7 — `handleVideoList` skips files already received

`GameViewModel.handleVideoList` now resolves each incoming title against `filesDir` *before* deciding whether to request a transfer. If the file already exists locally, the playlist entry's URI is rewritten to `Uri.fromFile(...)` on the spot and `receivedVideoFiles` is populated. Saves a roundtrip per video on every GM re-broadcast (new joiners, named-playlist load) and also recovers playlist URIs after a process restart where files survived but the in-memory set didn't.

### ● R8 — Purge stale milestone references in comments

Cleaned `M5.2` / `M5.1` / `M3` references from `activity_main.xml` (2 lines), `MainActivity.kt` (HID kdoc) and `GameViewModel.kt` (named-playlist section heading). No `Priority N` style references remained.

### ○ R9 — Refresh top-of-file architecture summary in `CLAUDE.md` if it's drifted

Pending verification — check after R1–R5 land.

---

## Process

- One item per commit (or per small batch of related items).
- Update this file as items move `○ → ◐ → ●`.
- For items with non-obvious design choices, append a short "**Decision:**" note inline.
- When all reliability items (R1, R2) are `●`, do a fresh field test before tackling the structural splits (R3, R4).
