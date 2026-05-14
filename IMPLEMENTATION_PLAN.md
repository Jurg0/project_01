# Roadmap

Forward-looking work, organized into milestones. Open items only — completed work is summarized in the archive at the bottom.

## How this document works

Work is grouped into thematic **milestones** (M1, M2, …). Items within a milestone are independent and can be tackled in any order; milestones themselves are loosely ordered by urgency for the next field test.

Each item carries a tag:

- 🐛 **BUG** — broken behavior; root-cause analysis included
- ⚙️ **FIX** — small targeted change
- 🎯 **REFINE** — improvement to existing behavior
- ✨ **FEATURE** — new capability

Status is denoted in the heading: `○` open, `◐` in progress, `●` done. Move to the Archive section when `●`.

---

## M1 — Stable Sessions

Blocker bugs. The next field test cannot run reliably until these are fixed.

### ● M1.1 🐛 Playlist controls inert during game session

**Symptom:** Delete / move-up / move-down on playlist items don't work after `Create Game`, even when the playlist is revealed via the GM overlay's "Playlist" toggle.

**Root cause:** `VideoAdapter.isGameMaster` is only set inside `showLobby()` (MainActivity.kt:546-548). At app start, `showLobby()` runs before a game exists, so `gameViewModel.isGameMaster()` returns `false` and the adapter flag is captured as `false`. When the GM then creates a game, `showGame()` does **not** refresh the flag — the per-row Move/Delete buttons stay hidden inside the row layout, so the user sees titles but no controls when toggling the playlist visible via the GM overlay.

**Fix applied:** `showGame()` now sets `videoAdapter.isGameMaster = isGameMaster` and calls `notifyDataSetChanged()`, mirroring `showLobby()`. The GM-overlay-toggle UX is unchanged (still hidden by default, revealed on tap) — when revealed, the rows now bind with their buttons.

**Files modified:** `MainActivity.kt:582-585`

### ● M1.2 🐛 Playlist controls vanish after End Game → new game cycle

**Symptom:** Game master ends a session, starts another, playlist controls no longer appear.

**Root cause:** Same bug as M1.1, observed at a later stage. `VideoAdapter.isGameMaster` was never refreshed in `showGame()`, only in `showLobby()`. After `endGame()`, `showLobby()` sets the flag to `false` (player is `null`); when the next game starts, `showGame()` did not re-set the flag.

**Fix:** Covered by the M1.1 change. Because `setGameStarted` uses `postValue` (async), by the time `showGame()` runs in the restart, `handleConnectionInfo` has already assigned `player` synchronously (GameViewModel.kt:138). So `isGameMaster()` returns `true` when `showGame()` reads it, and the M1.1 refresh now propagates that to the adapter.

**No additional code change required.**

### ● M1.3 🐛 Video transfer to players fails

**Symptom:** Players never actually receive videos.

**Root cause:** Three interlocking bugs:

1. **Address-type mismatch.** `FileTransferRequest.senderAddress` / `targetAddress` were filled with `WifiP2pDevice.deviceAddress` (a MAC address) and the GM's TCP-source address (an IP) respectively. The two values are in different namespaces and cannot be compared.

2. **Broadcast doesn't loop back.** A client (player) `broadcast()` only writes to its single OutputStream (to the server). The server (GM) receives it, but the player never receives its own broadcast. So the receive branch — gated on `thisDevice == targetAddress`, which only matches on the player — was unreachable: the player who would match never got the message.

3. **GM never matched either.** The outer guard `thisDevice == request.targetAddress` checks GM's address against the player's address — they don't match, so the send branch never fired.

Net result: nobody sent and nobody received. Worse, even if the GM had entered the branch, it called `sendFileWithRetry(request.senderAddress, …)` — using its own IP as the host to connect to, not the player's.

**Fix applied:**

- `handleFileTransferRequest(request, fromIp)` — now takes the TCP source IP from `NetworkEvent.DataReceived`. On the GM, it connects to that IP at `request.port` and sends. Player branch removed entirely (it was unreachable and the player initiates receiving on its own side anyway).
- `requestFileTransfer` (the player path) — now launches `startReceivingWithRetry(port, outputFile)` *before* broadcasting the request, so the player's `ServerSocket` is bound (or about to be) by the time the GM connects back. The existing exponential backoff in `sendFileWithRetry` covers any residual race.
- `handleGameSyncEvent` — passes `address` (the sender IP) to `handleFileTransferRequest`.

The `senderAddress` / `targetAddress` fields in `FileTransferRequest` are retained on the wire (informational only) so the protocol version doesn't have to bump.

**Files modified:** `GameViewModel.kt` (lines 206, 389-401, 635-655)

### ● M1.4 🐛 Players keep reconnecting after Game Master ends session

**Symptom:** GM presses `End Game`. Players' devices stay in a reconnect loop forever instead of returning to the initial lobby.

**Root cause:** Two interacting issues.
1. `handleEndGame()` didn't stop the reconnection manager. After GM tore down the group, the player's TCP socket dropped and `ClientDisconnected` fired, going straight into `startReconnecting()` at line 237.
2. `isEndingGame` was set to `true` at the start of `handleEndGame()` and to `false` at the end — but the disconnect event from the GM's group teardown arrives *after* `handleEndGame()` returns. So the `if (isEndingGame) return` guard at line 232 was already past, and the reconnect loop started.

**Fix applied:**
- `handleEndGame()` and `endGame()` both call `reconnectionManager.stopReconnecting()`.
- The trailing `isEndingGame = false` line is removed from both. The flag is now reset in `handleConnectionInfo()` (line 135) at the start of the next session — keeps it `true` across the entire end-game lifecycle, including the trailing disconnect event.
- Both paths also reset state to the initial app state: clear videos, clear player list, null `player` / `lastHost` / `lastPort`, post `DISCONNECTED`. The `setGameStarted(false)` call moved to the end of `handleEndGame()` so the lobby observer sees a fully-reset state.

**Files modified:** `GameViewModel.kt:355-373, 531-554`

### ● M1.5 🐛 Disconcerting white pulse during game session

**Symptom:** A continuous low-alpha white pulse runs over the game session, breaking immersion.

**Root cause:** `showGame()` started `pulseAnimator` unconditionally. The pulse was originally a "paused/idle" affordance; users found it more distracting than helpful.

**Fix applied:** Removed entirely.
- Deleted `pulse_overlay` View from `activity_main.xml`.
- Deleted `startPulseAnimation()` / `stopPulseAnimation()` methods.
- Deleted `pulseAnimator` field.
- Deleted `android.animation.ObjectAnimator` / `ValueAnimator` imports (no other callers).
- Deleted all 10 call sites.

**Files modified:** `MainActivity.kt`, `res/layout/activity_main.xml`

### ◐ M1.6 🐛 White strip at bottom of screen on Samsung S9

**Symptom:** A white strip appears at the bottom of the screen during game session on Samsung S9.

**Root cause:** Immersive mode (`WindowCompat.setDecorFitsSystemWindows(window, false)` + hiding `systemBars`) leaves the window background visible behind Samsung's gesture-nav hint area. The theme inherited `Theme.MaterialComponents.DayNight.DarkActionBar` without overriding `android:windowBackground`, so the default (white in day mode) showed through.

**Fix applied (code):**
- Added `android:windowBackground=@android:color/black` to `Theme.Project01` in `res/values/themes.xml`.
- Added `android:navigationBarColor=@android:color/black` to match — keeps the gesture-nav hint area consistent.
- `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` is already set on the `WindowInsetsController` in `showGame()`. Root `activity_main.xml` already has `android:background=@color/app_background` (#FF0A1929, near-black) so it won't visibly clash on the few frames before the FrameLayout is laid out.

**Files modified:** `res/values/themes.xml`

**Status `◐` — pending device validation on a Samsung S9.** Move to `●` once confirmed on hardware.

---

## M2 — Playback Experience

### ● M2.1 ⚙️ Video must fill the entire screen

**Symptom:** Letterbox / pillarbox depending on video aspect ratio vs. device screen. Black bars break the "anomaly" effect.

**Fix applied:** Added `app:resize_mode="zoom"` on the PlayerView in `activity_main.xml`. Preserves aspect ratio while cropping to fill — no bars.

**Files modified:** `res/layout/activity_main.xml`

### ● M2.2 🎯 Stop auto-advance — blue screen between videos

**Symptom:** Videos play in sequence automatically. Game master loses pacing control.

**Root cause:** Deeper than the initial guess. `STATE_ENDED` fires only at the end of the *playlist*, not between items — the `playNextVideo` call there was a no-op for in-between transitions. The actual auto-advance came from ExoPlayer's default playlist behavior: `setMediaItems(...)` makes the player auto-transition between items.

**Fix applied:**
- `exoPlayer.pauseAtEndOfMediaItems = true` in `initializePlayer()`. At the end of each item, ExoPlayer flips `playWhenReady` to `false` instead of advancing.
- `onIsPlayingChanged(false)` hides the video surface, exposing the blue safe-screen background. The GM also broadcasts the pause state to all clients, who hide their surfaces too.
- `gmNextButton` / `gmPreviousButton` (and the lobby `nextButton` / `previousButton`) now set `playWhenReady = true` after `seekToNext/Previous` so the new item actually plays — without this, the next item would load but stay paused.
- Removed the no-op `onPlaybackStateChanged` STATE_ENDED → `playNextVideo` handler. Deleted the now-unused `playNextVideo()` function from `GameViewModel` and its 3 tests.

The blue screen between videos is the natural "no anomaly" state in the game fiction — fits the narrative.

**Files modified:** `MainActivity.kt`, `GameViewModel.kt`, `GameViewModelTest.kt`

**Test count: 116 → 113.**

---

## M3 — Undercover Game Master

Game master's UI must be visually indistinguishable from a player's. Current overlay is too conspicuous.

### ● M3.1 ✨ Three-button game-session control surface

**Goal:** Reduce game-session controls to exactly three: **Previous**, **Play/Next**, **Light**.

**Fix applied:**
- `GameViewModel.setLights(on: Boolean)` — single GM action that broadcasts both screen and torch commands together.
- `gm_overlay` redesigned: three text-button glyphs (`‹` Previous, `▶` Play/Next, `○` Light) plus a smaller `•` glyph for the Playlist toggle. Removed `gm_play_pause_button`, `gm_screen_toggle_button`, `gm_torch_toggle_button`, `gm_add_video_button`, `gm_end_game_button`.
- Play/Next is smart: if playing → pause (back to blue safe-screen); if paused at end of item → seek to next + play; if paused mid-item → play current.
- Light is a single toggle: if both lights are on → turn both off (darkness mode); otherwise turn both on.
- End Game moved to a **long-press gesture** on `invisible_resume_button` (the same target as the double-tap reveal). Same confirmation dialog as before, no visible button. Standard long-press timeout (~500ms) — acceptable since the dialog already protects against accidents.

**Files modified:** `res/layout/activity_main.xml`, `MainActivity.kt`, `GameViewModel.kt`

### ● M3.2 🎯 Make remaining buttons less conspicuous

**Fix applied:**
- Switched from `OutlinedButton` style to `TextButton` style — no border/outline, blends with the overlay background.
- Glyph-only labels (`‹ ▶ ○ •`) — no English words to read at a glance.
- `#80FFFFFF` text color (~50% white) for the three main buttons, `#50FFFFFF` (~31%) for the Playlist toggle.
- Overlay background dimmed from `#DD000000` (87% black) to `#66000000` (40% black) — still readable but doesn't draw the eye.
- Padding reduced from 12dp to 4dp; button height reduced from 44dp to 40dp main / 28dp Playlist; collapsed three rows into two.

**Files modified:** `res/layout/activity_main.xml`

**Auto-hide on inactivity** was deferred — current overlay already requires a deliberate double-tap to reveal, and auto-hide adds state-machine complexity for marginal benefit.

---

## M4 — Bluetooth Remote (Presenter)

The three buttons from M3 must work from a Bluetooth presenter so the GM can control sessions hands-free / pocket-discreet.

### ◐ M4.1 ✨ Bluetooth presenter event recognition

**Fix applied:** `MainActivity.dispatchKeyEvent()` overridden. When the GM is in an active session, presenter HID key codes are captured and routed to the same three action methods M3 introduced (`onGmPrevious`, `onGmPlayNext`, `onGmToggleLight`).

Key mappings:

| Key codes | Action |
|---|---|
| `KEYCODE_DPAD_RIGHT`, `KEYCODE_PAGE_DOWN`, `KEYCODE_MEDIA_NEXT` | Play / Next |
| `KEYCODE_DPAD_LEFT`, `KEYCODE_PAGE_UP`, `KEYCODE_MEDIA_PREVIOUS` | Previous |
| `KEYCODE_DPAD_CENTER`, `KEYCODE_SPACE`, `KEYCODE_ENTER`, `KEYCODE_F5`, `KEYCODE_MEDIA_PLAY_PAUSE` | Light toggle |

The action runs on `ACTION_DOWN` (not on repeat), but both DOWN and UP are consumed so the OS doesn't double-act on the event. Volume keys are intentionally **not** mapped — they'd clash with the GM phone's own media volume during a session.

The GM pairs the presenter via Android system settings; no in-app discovery / connect step is required. Android delivers HID events to the focused window automatically.

**Files modified:** `MainActivity.kt`

**Status `◐` — pending field test with a real presenter (Logitech R400 / generic Bluetooth clicker).**

### ● M4.2 🎯 Single action handlers shared by touch + Bluetooth

**Fix applied:** The three M3 action handlers (`onGmPrevious`, `onGmPlayNext`, `onGmToggleLight`) are now the single source of behavior for both touch buttons and HID keys. No duplicate logic — adding a new presenter key code is a one-line change.

**SPP cleanup:** The legacy `BluetoothRemoteControl` SPP path was dead code (no UI entry, never called). With HID handling in place, it's superseded entirely. Deleted:

- `session/BluetoothRemoteControl.kt`
- `session/BluetoothDevicesDialogFragment.kt`
- `res/layout/dialog_bluetooth_devices.xml`
- `GameViewModel`: removed `bluetoothRemoteControl`, `_bluetoothDevices`, `bluetoothDevices`, `bluetoothReceiver`, `isBluetoothReceiverRegistered`, `startBluetoothDiscovery()`, `connectToBluetoothDevice()`, `handleRemoteControlMessage()`, `onPause()`. Trimmed unused imports.
- `MainActivity`: removed `bluetoothPermissions()` helper (unused) and `gameViewModel.onPause()` call.
- `AndroidManifest.xml`: dropped `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `ACCESS_COARSE_LOCATION` — no longer needed without discovery. Kept `BLUETOOTH` and `BLUETOOTH_CONNECT` for the `ACTION_REQUEST_ENABLE` prompt.

`initializeBluetooth()` survives and now only prompts the user to enable the Bluetooth radio so paired presenters can connect.

**Files modified:** `GameViewModel.kt`, `MainActivity.kt`, `AndroidManifest.xml`

---

## M5 — Pre-Production (Playlist Preparation)

Game master prepares playlists days or weeks before the session.

### ● M5.1 ✨ Persistent playlists

**Goal:** Save/load named playlists across app launches.

**Fix applied:**

- New `session/PlaylistStore.kt`: `savePlaylist(name, videos)`, `loadPlaylist(name)`, `listPlaylists()`, `deletePlaylist(name)`. Stores each playlist as JSON (`List<VideoDto>` serialized via existing kotlinx.serialization config) under `filesDir/playlists/<name>.json`. Underscore-prefixed names are reserved for internal slots and filtered from `listPlaylists()`.
- `GameRepository` exposes a `playlistStore` instance alongside `snapshotManager`.
- `GameViewModel.addVideo` / `removeVideo` / `moveVideoUp` / `moveVideoDown` route through a new `applyLocalVideoChange()` helper that auto-saves to the reserved `_last` slot on every mutation.
- `GameViewModel.init` calls `restoreLastPlaylist()` — on app start, the user sees their most recent playlist instead of an empty list. Guarded against empty/null returns so it's a no-op on first run.
- Public API: `savePlaylistAs(name)`, `loadNamedPlaylist(name)` (broadcasts to other devices if GM), `listSavedPlaylists()`, `deleteSavedPlaylist(name)`.
- Lobby UI: two new buttons `Save List` / `Load List` in `button_bar`. Save → name prompt; Load → list dialog with tap-to-load and a "Delete…" neutral button that opens a second list for deletion.

**URI persistence:** `MainActivity.openDocumentLauncher` already calls `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` at pick time (line 56), so stored URIs remain valid after restart. No change needed there.

**Files added:** `session/PlaylistStore.kt`, `test/.../PlaylistStoreTest.kt`

**Files modified:** `GameRepository.kt`, `GameViewModel.kt`, `MainActivity.kt`, `activity_main.xml`, `GameViewModelTest.kt` (added `mockPlaylistStore`)

**Tests:** 7 new `PlaylistStoreTest` cases — round-trip, missing playlist, sorted listing with underscore filtering, delete, blank-name rejection, path-separator rejection, hidden `_last` slot. Total tests: **120**.

### ● M5.2 🎯 Lobby video player: removed

**Symptom:** Lobby video player was dysfunctional, the single `exoPlayer` instance was shared across lobby and game modes with no distinct preview code path.

**Fix applied:** Removed the lobby player surface entirely.

- Added `android:id="@+id/player_container"` to the wrapping `ConstraintLayout`.
- `showLobby()` now hides `player_container`; `showGame()` shows it.
- Deleted the `playback_controls` `LinearLayout` (Prev / Play-Pause / Next buttons that only made sense for a lobby preview) and the three click handlers.
- The freed vertical space (weight 3 / 7) now goes to the playlist `lists_container` (which keeps its weight 4), so the playlist visibly stretches in lobby.

**Files modified:** `MainActivity.kt`, `res/layout/activity_main.xml`

---

## Backlog (deferred, not currently scheduled)

Carried from previous priority 22 — interesting but not blocking.

- **Remote volume control** — `AdvancedCommand` for volume so GM can fade audio at narrative moments.
- **Dark mode theme** — `values-night` override (DayNight theme already declared).
- **Haptic feedback** — GM-triggered vibration via `AdvancedCommand` for immersive moments.
- **Session logging** — record join times, playback timeline, etc. for post-session review.
- **QR code join** — encode group info + password in a QR; replaces Wi-Fi Direct manual discovery + password entry.
- **Cue system / branching narrative** — replace linear playlist with named cue points the GM triggers on demand.

---

## Recommended order

1. **M1** (blocker bugs) — must be done before next field test.
2. **M2** (playback) — small, high-impact polish that affects every session.
3. **M3** (undercover UI) — required for the game's premise to work.
4. **M4** (Bluetooth) — pairs naturally with M3 (the 3 buttons land in both places).
5. **M5** (persistent playlists) — quality-of-life for the GM, not required to run a session.

---

## Completed Work Archive

Priorities 1–22 (all done). Brief summary; see `git log` for detail.

| # | Title | Outcome |
|---|---|---|
| 1 | Crash Fixes & Data Corruption | Concurrent map use, manifest typo, BroadcastReceiver unregister guard, bounds checks, camera ID guard, socket leak on null host. |
| 2 | Resource Leaks & Lifecycle | observeForever leak, stream close, shutdown race, Bluetooth socket close, ExoPlayer double-init. |
| 3 | Architecture Refactoring | God-class extraction, repository encapsulation, activity-lifecycle separation, dead code deletion. |
| 4 | Robustness & Protocol | serialVersionUID (interim), `available()` fix, heartbeat health monitoring, configurable port. |
| 5 | Error Handling & Logging | `Log.e` everywhere, UI surfacing of errors. |
| 6 | Permissions | Explicit runtime permission flow, modern `ActivityResultContracts`. |
| 7 | Test Coverage | 82 → 114+ tests across SocketNetworkManager, GameViewModel, GameRepository, FileTransfer, serialization. |
| 8 | UI/UX Polish | DiffUtil, progressMap leak fix, ViewBinding, `exported` attr. |
| 9 | Serialization Migration | `ObjectInputStream` → `kotlinx.serialization` JSON with `MessageEnvelope`. |
| 10 | Reconnection Logic | `ReconnectionManager` with exponential backoff + jitter. |
| 11 | Password Security | Challenge-response (nonce + SHA-256). |
| 12 | Battery & Doze Mode | `ConnectionService` as foreground service. |
| 13 | FileTransfer Hardening | SHA-256 checksum, retry, 64 KB buffer. |
| 14 | Game State Snapshots | Periodic save + resume dialog. |
| 15 | Persistent Error UI | `UiError` sealed class (Recoverable / Informational / Critical), `ConnectionStatus` enum. |
| 16 | Signing & Release Build | Signing config, ProGuard rules, R8 enabled, `PROTOCOL_VERSION` handshake. |
| 17 | Accessibility | Content descriptions, touch targets, contrast, focus management, dialog labels. |
| 18 | App Size Optimization | Removed unused `media3-session` (ABI splits reverted — no savings). |
| 19 | Game Master In-Game Controls | GM overlay (double-tap to reveal), `EndGameMessage`, screen on/off toggle, torch on/off toggle. |
| 20 | Player Management | Player list population on TCP connect, custom player names. |
| 21 | Playback & Connectivity | Periodic position sync, graceful Wi-Fi Direct failure handling, portrait lock. |
| 22.1–22.2 | Player Status & Readiness | Battery + video-ready indicators per player. 22.3–22.8 deferred to Backlog above. |

### Operational (one-time, not tracked here)

- **Firebase Crashlytics** — Firebase project setup, `google-services.json`, Gradle plugins, `recordException()` integration. Outside normal code-change workflow; covered separately when ready.
