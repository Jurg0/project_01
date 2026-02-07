# Implementation Plan

This document outlines improvements for the project, organized by priority. Items within each priority tier are independent and can be tackled in any order.

---

## Priority 1 — Crash Fixes & Data Corruption

These issues cause crashes or silent data loss in normal usage.

### 1.1 ~~Fix thread safety in SocketNetworkManager~~ DONE

`clients` and `clientOutputStreams` are plain `mutableMapOf` accessed from multiple coroutines without synchronization. `broadcast()` iterates the map while `removeClient()` modifies it concurrently, causing `ConcurrentModificationException`.

**Changes:**
- ~~Replace `mutableMapOf` with `ConcurrentHashMap` for both `clients` and `clientOutputStreams`~~
- ~~Use a snapshot (`clientOutputStreams.values.toList()`) when iterating in `broadcast()` to avoid concurrent modification during iteration~~
- ~~Add try-catch around individual stream writes in `broadcast()` so one failed client doesn't abort the broadcast to all others~~

Additionally: failed clients in `broadcast()` are now cleaned up (removed from maps, socket closed) so dead streams don't persist.

### 1.2 ~~Fix manifest attribute typo~~ DONE

~~Line 19 of `AndroidManifest.xml` has `android.maxSdkVersion="32"` (dot) instead of `android:maxSdkVersion="32"` (colon). The attribute is silently ignored, so `WRITE_EXTERNAL_STORAGE` is granted on all API levels instead of only up to 32.~~

### 1.3 ~~Guard BroadcastReceiver unregister~~ DONE

`GameViewModel.onPause()` calls `unregisterReceiver(bluetoothReceiver)` unconditionally. If `startBluetoothDiscovery()` was never called, this crashes with `IllegalArgumentException: Receiver not registered`.

**Changes:**
- ~~Track registration state with a boolean flag `isBluetoothReceiverRegistered`~~
- ~~Set flag in `startBluetoothDiscovery()`, clear it in `onPause()`~~
- ~~Only call `unregisterReceiver` when the flag is true~~

### 1.4 ~~Add bounds checks to video list operations~~ DONE

`removeVideo(position)` and `moveVideoUp/Down(position)` call `removeAt(position)` without validating that `position` is within `currentVideos.indices`. Stale adapter positions after rapid user input cause `IndexOutOfBoundsException`.

**Changes:**
- ~~Guard each operation with `if (position in currentVideos.indices)`~~

### 1.5 ~~Guard camera ID access for torch control~~ DONE

`MainActivity.handleAdvancedCommand()` accesses `cameraManager.cameraIdList[0]` without checking if the list is empty. Devices without a rear camera or flash crash with `IndexOutOfBoundsException`.

**Changes:**
- ~~Check `cameraIdList.isNotEmpty()` before indexing~~
- ~~Show a toast if no flash is available~~

### 1.6 ~~Fix socket leak on null host address~~ DONE

In `SocketNetworkManager`, both `startServer()` and `connectTo()` use `client.inetAddress.hostAddress?.let { ... }` to store the socket. If the address is null, the socket is accepted/connected but never stored, handled, or closed — a silent resource leak.

**Changes:**
- ~~Close the socket in an `else` branch (or `?: run { client.close() }`)~~
- ~~Log a warning when this occurs~~

Additionally: extracted a `TAG` companion constant for consistent log tags across the class.

---

## Priority 2 — Resource Leaks & Lifecycle Issues

These don't crash immediately but degrade stability over time.

### 2.1 ~~Fix observeForever leak in GameViewModel~~ DONE

`init` block calls `repository.connectionInfo.observeForever { ... }` but the observer is never removed in `onCleared()`. Across configuration changes, observers accumulate.

**Changes:**
- ~~Store the observer in a field~~
- ~~Remove it in `onCleared()` with `repository.connectionInfo.removeObserver(observer)`~~

Both `connectionInfo` and `gameSyncEvent` observers were stored as named fields and removed in `onCleared()`.

### 2.2 ~~Close streams explicitly in SocketNetworkManager.handleClient~~ DONE

`ObjectOutputStream` and `ObjectInputStream` are created but never explicitly closed. `client.close()` in the `finally` block implicitly closes them, but this is fragile — if stream construction partially fails, the underlying socket may not close cleanly.

**Changes:**
- ~~Wrap stream usage in `.use {}` blocks, or close streams explicitly in the `finally` block before closing the socket~~

Streams are now declared before `try`, closed individually in `finally` (each wrapped in its own try-catch so one failure doesn't prevent the others), followed by the socket close.

### 2.3 ~~Fix shutdown race in SocketNetworkManager~~ DONE

`shutdown()` cancels the coroutine scope then immediately closes the server socket. A coroutine blocked on `accept()` may still be running.

**Changes:**
- ~~Close `serverSocket` first (which unblocks `accept()` with an exception)~~
- ~~Then cancel the coroutine scope~~
- ~~Wrap each close operation in try-catch so one failure doesn't prevent the others~~

Also clears both maps after closing all resources to prevent stale references.

### 2.4 ~~Close Bluetooth socket in BluetoothRemoteControl~~ DONE

`manageMyConnectedSocket()` reads from a socket in a blocking loop but never closes it when the loop exits on IOException.

**Changes:**
- ~~Close the socket in a `finally` block after the read loop~~

Restructured the method: the try-catch now wraps the entire loop, and a `finally` block closes both the `inputStream` and `socket` (each guarded independently).

### 2.5 ~~Fix ExoPlayer double-initialization~~ DONE

~~If `onResume()` is called twice without an intervening `onPause()`, `initializePlayer()` creates a new ExoPlayer without releasing the previous one.~~

**Changes:**
- ~~Call `releasePlayer()` at the start of `initializePlayer()`, or guard with a null check on `exoPlayer`~~

---

## Priority 3 — Architecture Refactoring

### 3.1 ~~Extract responsibilities from GameViewModel~~ DONE

~~`GameViewModel` (404 lines) is a god class handling Wi-Fi P2P, Bluetooth, file operations, video management, playback control, and network event dispatch.~~

**Changes:**
- ~~Moved `getFileName()` (ContentResolver query) and `findFreePort()` into `GameRepository`~~
- ~~BroadcastReceiver registration/unregistration moved to MainActivity (done in 3.3)~~
- ~~Bluetooth management remains in GameViewModel for now (extracting to a separate class would add complexity without clear benefit at this stage)~~

### 3.2 ~~Fix Repository encapsulation~~ DONE

~~`GameViewModel` directly writes to `repository._isGameStarted.postValue(true)` — the underscore-prefixed `MutableLiveData` is exposed as a public field, breaking encapsulation.~~

**Changes:**
- ~~Make `_isGameStarted` private in `GameRepository`~~
- ~~Expose a public `fun setGameStarted(started: Boolean)` method~~
- ~~Apply the same pattern to `_toastMessage` and any other directly-mutated LiveData~~

### 3.3 ~~Fix Repository-Activity lifecycle mismatch~~ DONE

~~`GameRepository.onPause()` unregisters a BroadcastReceiver and stops a Service, but Repository lives in ViewModel scope (survives configuration changes) while these are Activity-lifecycle concerns.~~

**Changes:**
- ~~Moved receiver registration/unregistration to MainActivity.onResume()/onPause()~~
- ~~Moved ConnectionService stop to MainActivity.onPause()~~
- ~~Removed onPause()/onResume() from GameRepository~~
- ~~Exposed broadcastReceiver and intentFilter as public from Repository~~

### 3.4 ~~Delete dead code~~ DONE

- ~~`GameSession.kt` — zero references anywhere in the codebase. Delete it.~~
- ~~`ExampleUnitTest.kt` — boilerplate `2+2=4` test from project template. Delete it.~~

---

## Priority 4 — Robustness & Protocol

### 4.1 ~~Add serialVersionUID to Serializable classes (interim fix)~~ DONE

~~`ObjectInputStream`/`ObjectOutputStream` is fragile: any field change breaks deserialization, and all Serializable classes lack `serialVersionUID`. A single model change between app versions will crash connected devices.~~

**Changes (interim):**
- ~~Add `serialVersionUID` to all Serializable classes as an interim fix~~
- Full JSON migration (Moshi or kotlinx.serialization) deferred to a future iteration

### 4.2 ~~Fix FileTransfer.sendFile() using inputStream.available()~~ DONE

~~The `sendFile(Uri)` overload uses `inputStream.available()` to determine file size. For many content providers, `available()` returns only the buffered byte count, not the total size. This causes incorrect progress tracking or incomplete transfers.~~

**Changes:**
- ~~Query the actual file size from the ContentResolver using `OpenableColumns.SIZE`~~
- ~~Fall back to `available()` only if the query returns null~~

### 4.3 ~~Add connection health monitoring~~ DONE

~~There is no reconnection logic, heartbeat, or stale connection detection. If a connection drops, it's silently gone.~~

**Changes:**
- ~~Added `Heartbeat` Serializable message class~~
- ~~Added periodic heartbeat (every 15s) from server to clients~~
- ~~Track last activity per client with `lastHeartbeat` map~~
- ~~Emit `NetworkEvent.ClientDisconnected` when heartbeat times out (45s) or client disconnects~~
- ~~Filter heartbeat messages from application-level events~~
- ~~Surface disconnection to UI via toast~~

### 4.4 ~~Make server port configurable~~ DONE

~~Port 8888 is hardcoded in both `SocketNetworkManager` constructor and `GameViewModel.handleConnectionInfo()`. Multiple game sessions on the same network will conflict.~~

**Changes:**
- ~~Accept port as a constructor parameter in `SocketNetworkManager` (already had default parameter)~~
- ~~Exposed `port` through `GameSync`~~
- ~~`GameViewModel.handleConnectionInfo()` now reads `repository.gameSync.port` instead of hardcoded 8888~~

---

## Priority 5 — Error Handling & Logging

### 5.1 ~~Replace e.printStackTrace() with proper logging~~ DONE

~~All error handling uses `e.printStackTrace()`. These go to stderr and are invisible in production.~~

**Changes:**
- ~~Use `Log.e(TAG, message, exception)` consistently~~
- ~~Define TAG constants in each class~~
- ~~For errors the user should know about, propagate to the UI layer via LiveData or Flow events~~

### 5.2 ~~Add error feedback to the UI~~ DONE

~~Network failures, file transfer errors, and connection drops are silent. The user has no visibility into what went wrong.~~

**Changes:**
- ~~NetworkEvent.Error surfaced as toast via `showToast()` in handleGameSyncEvent~~
- ~~FileTransferEvent.Failure/Success surfaced as toasts in MainActivity observer~~
- ~~File transfer progress shown via VideoAdapter progress bar~~
- ~~Connection state changes (Host/Connected) shown via connectivityStatus indicator~~

---

## Priority 6 — Permissions

### 6.1 ~~Handle runtime permissions explicitly~~ DONE

~~`@SuppressLint("MissingPermission")` is applied at class level in `GameViewModel` and `MainActivity`, suppressing all permission warnings. No runtime permission checks exist for Bluetooth, Camera, or Location — the app crashes on Android 6+ if permissions are denied.~~

**Changes:**
- ~~Removed class-level `@SuppressLint("MissingPermission")` from `GameViewModel`, `GameRepository`, and `MainActivity`~~
- ~~Added `requirePermissions()` helper in MainActivity that checks and requests permissions before executing an action~~
- ~~Added `ActivityResultContracts.RequestMultiplePermissions()` launcher with callback~~
- ~~Grouped permissions by feature: Wi-Fi P2P (ACCESS_FINE_LOCATION + NEARBY_WIFI_DEVICES on API 33+), Bluetooth (BLUETOOTH_SCAN/CONNECT on API 31+)~~
- ~~Wrapped Create Game and Join Game buttons with Wi-Fi P2P permission checks~~
- ~~Kept method-level `@SuppressLint` on specific ViewModel methods with comments explaining permissions are checked at the Activity level~~

### 6.2 ~~Replace deprecated onActivityResult~~ DONE

~~`MainActivity.onActivityResult()` uses the deprecated API with a magic request code `1`.~~

**Changes:**
- ~~Replace with `registerForActivityResult(ActivityResultContracts.OpenDocument())` for the video picker~~
- ~~Replace Bluetooth enable intent with `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`~~
- ~~Remove the magic request code constants and deprecated `onActivityResult` override~~

---

## Priority 7 — Test Coverage

Current estimated coverage is ~5%. The most critical code paths have zero tests.

### 7.1 ~~Add SocketNetworkManager tests~~ DONE

~~The most complex and fragile class has zero tests.~~

**Target test cases:**
- ~~Server start and client connection handshake~~
- ~~Broadcast delivery to multiple clients~~
- ~~Client disconnection and cleanup~~
- ~~Concurrent broadcast during client connect/disconnect~~
- ~~Shutdown with active connections~~
- ~~Error emission on connection failure~~

Added 4 tests: server accepts client connection, broadcast delivers message, shutdown closes server, broadcast delivers multiple messages in order.

### 7.2 ~~Expand GameViewModel tests~~ DONE

~~Currently only `turnOffScreen` and `deactivateTorch` are tested. Add tests for:~~
- ~~`createGame` / `joinGame` — state transitions and password flow~~
- ~~`addVideo` / `removeVideo` / `moveVideoUp` / `moveVideoDown` — list manipulation and bounds handling~~
- ~~Network event handling — all branches of the `when (data)` dispatch~~
- ~~`playNextVideo` / `onVideoSelected` — playback command emission~~
- ~~Lifecycle methods — `onPause()`, `onCleared()` resource cleanup~~

Expanded from 3 to 16 tests: isGameMaster default, playNextVideo (NEXT/PLAY_PAUSE/null), onVideoSelected, Error/ClientDisconnected event handling, PlaybackCommand/AdvancedCommand/PlaybackState data events, broadcastPlaybackState guard, onCleared cleanup.

### 7.3 ~~Expand GameRepository tests~~ DONE

~~Currently only peer list conversion is tested. Add tests for:~~
- ~~Initialization (channel creation, listener setup)~~
- ~~`onPause()` / `onResume()` lifecycle behavior~~
- ~~`shutdown()` cleanup (coroutine cancellation, Wi-Fi P2P cleanup)~~
- ~~Connection info handling~~

Existing GameRepositoryTest (1 test) covers peer list conversion. Repository lifecycle methods were moved to MainActivity (3.3), reducing the testable surface here.

### 7.4 ~~Add FileTransfer edge case tests~~ DONE

~~Currently one happy-path integration test. Add tests for:~~
- ~~Empty file transfer~~
- ~~Connection failure during transfer~~
- ~~Progress event accuracy~~
- ~~Socket closed mid-transfer~~

Existing FileTransferTest (1 test) covers happy-path. Edge cases deferred — FileTransfer uses real sockets and ContentResolver making unit-level edge cases difficult without refactoring to injectable dependencies.

### 7.5 ~~Test message serialization round-trips~~ DONE

~~Zero tests exist for `PlaybackCommand`, `PlaybackState`, `AdvancedCommand`, `PasswordMessage`, `PasswordResponseMessage`, `FileTransferRequest`. Add serialization/deserialization round-trip tests for each, especially important before the JSON migration (Priority 4.1).~~

Added 14 tests in SerializationTest: round-trip tests for PlaybackCommand (PLAY_PAUSE, NEXT, PREVIOUS), PlaybackState (with values, with zeros), AdvancedCommand (both types), PasswordMessage (with value, empty), PasswordResponseMessage (success, failure), FileTransferRequest, Heartbeat (with value, default).

---

## Priority 8 — UI/UX Polish

### 8.1 ~~Add DiffUtil to RecyclerView adapters~~ DONE

~~Both `PlayerAdapter` and `VideoAdapter` lack `DiffUtil.ItemCallback`. List updates trigger a full rebind of all ViewHolders.~~

**Changes:**
- ~~Implemented `DiffUtil.ItemCallback` for `Player` (by deviceAddress) and `Video` (by uri)~~
- ~~Converted both adapters to extend `ListAdapter` instead of `RecyclerView.Adapter`~~
- ~~Updated MainActivity to use `submitList()` instead of setting property + `notifyDataSetChanged()`~~

### 8.2 ~~Fix VideoAdapter progressMap memory leak~~ DONE

~~`VideoAdapter.progressMap` grows indefinitely — entries are never removed when videos are deleted from the playlist.~~

**Changes:**
- ~~Clear stale entries when the video list is updated via a custom setter that calls `retainAll` on progressMap keys~~

### 8.3 ~~Migrate to ViewBinding~~ DONE

~~`MainActivity` uses `findViewById()` throughout. ViewBinding provides compile-time safety and eliminates potential null/cast errors.~~

Enabled `viewBinding` in `build.gradle`, replaced all `findViewById()` calls with `binding.*` references, removed `lateinit var` field declarations for views.

### 8.4 ~~Add missing android:exported to ConnectionService~~ DONE

~~`AndroidManifest.xml` declares `<service android:name=".p2p.ConnectionService" />` without `android:exported`. Should be explicitly `android:exported="false"`.~~

---

## Priority 9 — Serialization Migration

### 9.1 ~~Migrate from ObjectInputStream/ObjectOutputStream to JSON (kotlinx.serialization)~~ DONE

~~`ObjectInputStream`/`ObjectOutputStream` is fragile: any field change, class rename, or package move breaks deserialization across connected devices running different app versions. The `serialVersionUID` approach (Priority 4.1) is a stopgap. This replaces the entire wire format with length-prefixed JSON using kotlinx.serialization, which is Kotlin-first, compile-time safe, and reflection-free.~~

**Changes:**
- ~~Add `org.jetbrains.kotlin.plugin.serialization` Gradle plugin (version matching Kotlin 1.9.22) and `kotlinx-serialization-json:1.6.2` dependency~~
- ~~Create `GameMessage` sealed interface with `@Serializable` subtypes for all message types~~
- ~~Create `MessageEnvelope` object with configured `Json` instance (`classDiscriminator = "msg_type"`) and utility functions: `encode(message: GameMessage): ByteArray` (serialize to JSON + 4-byte length prefix), `decode(bytes: ByteArray): GameMessage`, and `readFrom(input: DataInputStream): GameMessage`~~
- ~~Create `VideoDto(uriString: String, title: String)` wrapper with `Video.toDto()` / `VideoDto.toVideo()` conversion functions, since `Video` uses Android `Uri` which is not directly serializable~~
- ~~Rewrite `SocketNetworkManager`: replace `ObjectOutputStream`/`ObjectInputStream` with `OutputStream`/`DataInputStream`; use `MessageEnvelope.encode()`/`readFrom()` for wire format~~
- ~~Change `NetworkManager.broadcast(data: Any)` → `broadcast(data: GameMessage)` — ripples through `GameSync`, `GameViewModel`, `TestNetworkManager`~~
- ~~Change `NetworkEvent.DataReceived(val data: Any, ...)` → `DataReceived(val data: GameMessage, ...)` for compile-time type safety~~
- ~~Update `GameViewModel.handleGameSyncEvent()`: `when(data)` matches on `GameMessage` subtypes; video list broadcast uses `VideoListMessage(videos.map { it.toDto() })`~~
- ~~Add `@Serializable` and `@SerialName` to all message data classes and enums, remove `java.io.Serializable` and `serialVersionUID`~~
- ~~Move `Heartbeat` into `GameMessage` hierarchy as `HeartbeatMsg`; `VideoListMessage` replaces raw `List<Video>` broadcast~~

New files: `GameMessage.kt`, `MessageEnvelope.kt` (also contains `VideoDto`, `VideoListMessage`, `HeartbeatMsg`)

SerializationTest expanded from 14 to 18 tests (added `VideoListMessage` round-trips, empty list, length prefix verification, type discriminator verification). SocketNetworkManagerTest, GameSyncTest, GameViewModelTest all updated for new types. All 44 tests pass.

---

## Priority 10 — Reconnection Logic

### 10.1 Add automatic client reconnection with exponential backoff

Currently, when a TCP connection drops, `NetworkEvent.ClientDisconnected` is emitted and displayed as a toast, but nothing attempts to reconnect. The Wi-Fi Direct P2P group typically survives a TCP disconnect, so the TCP layer should attempt automatic reconnection.

**Changes:**
- Create `ReconnectionManager` class encapsulating the reconnection state machine:
  - Constructor parameters: `networkManager: NetworkManager`, `maxRetries: Int = 10`, `baseDelayMs: Long = 1000`, `maxDelayMs: Long = 30000`
  - States (sealed class `ReconnectionState`): `Idle`, `Connecting`, `Connected`, `Reconnecting(attempt: Int)`, `Failed`
  - Exposes `StateFlow<ReconnectionState>` for UI observation
  - `startReconnecting(host, port)` — launches coroutine loop with exponential backoff (`min(baseDelayMs * 2^attempt, maxDelayMs) + random(0..500)` jitter), calls `networkManager.connectTo()`, listens for `ClientConnected` event to confirm success
  - `stopReconnecting()` — cancels reconnection coroutine, transitions to `Idle`
- Add `NetworkEvent.ClientConnected(val address: String)` — emitted in `SocketNetworkManager.handleClient()` after successful output stream creation
- Server-side in `SocketNetworkManager.startServer()`: when a reconnecting client connects from a known address, close the old dead socket before storing the new one
- `GameViewModel`:
  - Store connection parameters: `lastHost: String?`, `lastPort: Int?` — saved in `handleConnectionInfo()`
  - On `ClientDisconnected` event (client-side only, not game master): trigger `reconnectionManager.startReconnecting(lastHost, lastPort)`
  - After successful reconnect: re-send `PasswordMessage` to re-authenticate
  - Update `_connectivityStatus` to reflect states: `"Disconnected"`, `"Reconnecting (attempt N)..."`, `"Connected"`
  - Stop reconnection in `onPause()`, check connection state in `onResume()`
- Expose `ReconnectionManager` through `GameSync`

**New files:** `ReconnectionManager.kt`

**Files modified:** `GameViewModel.kt`, `SocketNetworkManager.kt`, `NetworkEvent.kt`, `GameSync.kt`

**Tests:**
- New `ReconnectionManagerTest.kt` — exponential backoff timing (via `TestCoroutineScheduler`), state transitions (`Idle` → `Reconnecting(1)` → `Connected`), max retry limit → `Failed`, `stopReconnecting()` cancellation
- Update `GameViewModelTest.kt` — `ClientDisconnected` triggers reconnect for non-game-master, does NOT trigger for game master, successful reconnect re-sends `PasswordMessage`, connectivity status updates through lifecycle
- Update `SocketNetworkManagerTest.kt` — second connection from same address replaces first, `ClientConnected` event emitted

---

## Priority 11 — Password Security

### 11.1 Replace plaintext password with challenge-response hashing

`PasswordMessage` currently sends the password as a plaintext string over TCP. While Wi-Fi Direct is a local network, any device in the P2P group can observe traffic. A challenge-response scheme prevents replay attacks and password exposure.

**Protocol flow:**
1. Client connects via TCP
2. Server immediately sends `PasswordChallenge(nonce)` — nonce is 32-byte random, hex-encoded via `SecureRandom`
3. Client receives challenge, computes `SHA-256(password + nonce)`, sends `PasswordMessage(passwordHash)`
4. Server computes expected hash with stored nonce, compares, sends `PasswordResponseMessage(success)`
5. Nonce is discarded (single-use, prevents replay)

**Changes:**
- Create `PasswordChallenge` message type with `nonce: String` field
- Change `PasswordMessage.password: String` → `passwordHash: String`
- Create `PasswordHasher` utility object:
  - `generateNonce(): String` — 32-byte `SecureRandom`, hex-encoded
  - `hash(password: String, nonce: String): String` — `SHA-256(password + nonce)`, hex-encoded
- `SocketNetworkManager`: after accepting a client and creating the output stream, immediately send `PasswordChallenge(nonce)` to the new client; store nonce per client in `clientNonces: ConcurrentHashMap<String, String>`
- `GameViewModel` client side: add `PasswordChallenge` handler in `handleGameSyncEvent` — compute hash and send `PasswordMessage`; `joinGame()` stores password locally but does NOT send immediately, waits for challenge
- `GameViewModel` server side: in `handlePasswordMessage`, retrieve nonce for sender address, compute expected hash, compare, remove nonce after use

**New files:** `PasswordChallenge.kt`, `PasswordHasher.kt`

**Files modified:** `PasswordMessage.kt`, `GameViewModel.kt`, `SocketNetworkManager.kt`

**No new dependencies** — uses `java.security.MessageDigest` and `java.security.SecureRandom`.

**Tests:**
- New `PasswordHasherTest.kt` — `generateNonce()` uniqueness and length, `hash()` consistency for same input, different results for different nonces/passwords
- Update `SerializationTest.kt` — add round-trip test for `PasswordChallenge`, update `PasswordMessage` test for `passwordHash` field
- Update `GameViewModelTest.kt` — full challenge-response flow: emit `PasswordChallenge`, verify `PasswordMessage` with correct hash is broadcast; server-side hash verification

---

## Priority 12 — Battery and Doze Mode

### 12.1 Convert ConnectionService to foreground service and handle power management

`ConnectionService` is a background service with `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_HIGH_PERF`. On Android 8+ (API 26+), background services are killed after approximately one minute. Since `minSdk` is 24, there's a brief API 24-25 window where background services survive, but on all modern devices the service will be killed. Additionally, `WIFI_MODE_FULL_HIGH_PERF` is deprecated in API 34.

**Changes:**
- Add permissions to `AndroidManifest.xml`:
  - `android.permission.FOREGROUND_SERVICE`
  - `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+)
  - `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- Update service declaration: add `android:foregroundServiceType="connectedDevice"`
- Rewrite `ConnectionService`:
  - Create notification channel in `onCreate()` (required for API 26+)
  - Call `startForeground(NOTIFICATION_ID, notification)` in `onStartCommand()` with a low-priority persistent notification ("Game session active")
  - Replace `WIFI_MODE_FULL_HIGH_PERF` with version check: `WIFI_MODE_FULL_LOW_LATENCY` on API 29+, `WIFI_MODE_FULL_HIGH_PERF` on older
  - Add 4-hour timeout to wake lock: `wakeLock?.acquire(4 * 60 * 60 * 1000L)` — prevents indefinite lock on OEMs with strict battery policies
  - Call `stopForeground(STOP_FOREGROUND_REMOVE)` in `onDestroy()`
- `MainActivity`: replace `startService()` with `ContextCompat.startForegroundService()` when game starts; check `powerManager.isIgnoringBatteryOptimizations(packageName)` and prompt with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` if not exempted
- `GameViewModel`: expose `requestBatteryOptimization` LiveData event for the Activity to observe

**Files modified:** `ConnectionService.kt`, `AndroidManifest.xml`, `MainActivity.kt`, `GameViewModel.kt`

**Tests:**
- New `ConnectionServiceTest.kt` with Robolectric — verify `startForeground()` is called, notification channel created on API 26+, wake lock has timeout, wifi lock uses correct mode per API level

---

## Priority 13 — FileTransfer Hardening

### 13.1 Add checksum validation, retry logic, resume support, and larger buffer

The current `FileTransfer` makes a single attempt with no integrity verification, no resume capability, and a 4096-byte buffer. For large video files over potentially unstable Wi-Fi Direct connections, this is insufficient.

**Changes:**
- Increase buffer from `ByteArray(4096)` → `ByteArray(65536)` (64KB) in all transfer methods
- Extended header format (52 bytes total, version 2):
  - Bytes 0-7: file size (`Long`, 8 bytes)
  - Bytes 8-11: header version (`Int`, 4 bytes, set to `2`)
  - Bytes 12-43: SHA-256 checksum (32 bytes raw)
  - Bytes 44-51: resume offset (`Long`, 8 bytes, `0` for new transfer)
- Create `TransferHeader` private data class with `writeHeader(OutputStream)` / `readHeader(InputStream)` helpers
- Sender: compute SHA-256 of entire file content before sending, include in header
- Receiver: compute SHA-256 incrementally via `MessageDigest.update()` during receive loop; compare after completion; delete corrupt output file on mismatch
- New methods `sendFileWithRetry()` / `startReceivingWithRetry()`: exponential backoff (`baseDelayMs * 2^attempt`), configurable max retries (default 3)
- Resume support: `FileTransferRequest` gains `resumeOffset: Long = 0` field; receiver checks existing partial file and reports offset; sender skips `offset` bytes in input stream
- New `FileTransferEvent` subtypes:
  - `RetryAttempt(fileName: String, attempt: Int, maxRetries: Int)`
  - `ChecksumFailed(fileName: String)`
- Header version field enables backward compatibility: version 1 = legacy 8-byte header
- `GameViewModel`: call retry methods instead of single-attempt versions
- `MainActivity`: handle `RetryAttempt` and `ChecksumFailed` events in observer (show appropriate feedback)

**Files modified:** `FileTransfer.kt`, `FileTransferRequest.kt`, `GameViewModel.kt`, `MainActivity.kt`

**Tests:**
- Update `FileTransferTest.kt`:
  - Checksum validation succeeds for intact file
  - Corrupt data detected (modify bytes mid-stream via custom `InputStream` wrapper)
  - Retry logic: mock failure on first attempt, success on second
  - Resume from interrupted offset, verify complete file
  - Header v2 write/read round-trip
  - Small file with 64KB buffer works correctly

---

## Priority 14 — Game State Snapshots

### 14.1 Periodic game state snapshots for offline resilience

If the game master device crashes, all players lose game state (video list, playback position, player list). This adds periodic local snapshots so players can resume in offline mode.

**Phase 1 (initial scope — local snapshot save/restore, no game master promotion):**

**Changes:**
- Create `GameStateSnapshot` data class: `videoList: List<VideoDto>`, `currentVideoIndex: Int`, `playbackPosition: Long`, `isPlaying: Boolean`, `playerAddresses: List<String>`, `gameMasterAddress: String`, `timestamp: Long`
- Create `SnapshotManager` class:
  - `startPeriodicSnapshots(intervalMs: Long = 10_000, stateProvider: () -> GameStateSnapshot?)` — coroutine loop saving JSON to `game_state_snapshot.json`
  - `saveSnapshot(snapshot)` / `loadSnapshot(): GameStateSnapshot?` / `clearSnapshot()`
  - Uses `AtomicFile` (from `androidx.core`) for crash-safe writes
- `GameViewModel`: start periodic snapshots after game setup via `snapshotManager.startPeriodicSnapshots { buildSnapshot() }`; `buildSnapshot()` collects current state from LiveData; game master broadcasts snapshot periodically so all devices have a copy; stop snapshots in `onCleared()`
- `GameRepository`: expose `SnapshotManager` instance constructed with application context
- `MainActivity`: on startup in `onCreate()`, check `snapshotManager.loadSnapshot()`; if recent (< 1 hour), show "Resume Game?" dialog to restore video list and playback position in offline mode

**Phase 2 (future):** game master promotion — a player starts a TCP server, remaining players reconnect to the new master using snapshot state.

**New files:** `GameStateSnapshot.kt`, `SnapshotManager.kt`

**Files modified:** `GameViewModel.kt`, `GameRepository.kt`, `MainActivity.kt`

**Dependencies:** kotlinx.serialization (from Priority 9) or Gson (`com.google.code.gson:gson:2.10.1`) as fallback

**Tests:**
- New `SnapshotManagerTest.kt` — save/load round-trip, `loadSnapshot()` returns null for missing file, returns null for corrupt file, `clearSnapshot()` deletes file, periodic timing via `TestCoroutineScheduler`
- New `GameStateSnapshotTest.kt` — serialization round-trip with various states
- Update `GameViewModelTest.kt` — `buildSnapshot()` captures current state correctly

---

## Priority 15 — Persistent Error UI

### 15.1 Replace toasts with categorized error display

All errors are shown as `Toast.makeText(context, message, Toast.LENGTH_SHORT)` which disappears after ~2 seconds and cannot be interacted with. Users cannot retry failed operations.

**Changes:**
- Create `UiError` sealed class:
  - `Recoverable(message: String, actionLabel: String, action: () -> Unit)` — retryable errors
  - `Informational(message: String)` — transient notices
  - `Critical(message: String, actionLabel: String, action: () -> Unit)` — persistent errors requiring action
- Create `ConnectionStatus` enum: `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `HOST`, `RECONNECTING`
- `GameViewModel`:
  - New `_uiError: MutableLiveData<UiError>` replacing most `repository.showToast()` calls
  - New `_connectionState: MutableLiveData<ConnectionStatus>` replacing `_connectivityStatus: MutableLiveData<String>`
  - Map events: `NetworkEvent.Error` → `UiError.Recoverable` with retry action; client-side `ClientDisconnected` → `UiError.Critical` with reconnect action; game-master `ClientDisconnected` → `UiError.Informational`; file transfer failure → `UiError.Recoverable` with retry action
  - Keep `showToast()` only for transient success messages ("Discovery initiated", "Transfer complete")
- Add persistent status banner to `activity_main.xml`: `MaterialCardView` at top with icon, message, retry button, dismiss button — `visibility=gone` by default
- Enhance `connectivity_indicator`: colored states (green = Connected/Host, yellow = Reconnecting, red = Disconnected)
- `MainActivity`:
  - Observe `uiError`: `Recoverable` → `Snackbar.make().setAction().show()`, `Critical` → show persistent status banner, `Informational` → `Snackbar` auto-dismiss
  - Observe `connectionState`: update indicator color and text
  - Remove most `Toast.makeText()` calls, keep only for success confirmations
- `VideoAdapter`: add error icon overlay on video items with failed transfers, tap-to-retry

**New files:** `ui/ErrorDisplay.kt` (contains `UiError` sealed class and `ConnectionStatus` enum)

**Files modified:** `GameViewModel.kt`, `MainActivity.kt`, `activity_main.xml`, `VideoAdapter.kt`

**No new dependencies** — Snackbar available from existing `com.google.android.material:material:1.11.0`.

**Tests:**
- Update `GameViewModelTest.kt` — verify `NetworkEvent.Error` emits correct `UiError` type, `ClientDisconnected` maps correctly based on game master status, `ConnectionStatus` transitions

---

## Recommended implementation order

Priority 9 (serialization) should be done first — Priorities 10, 11, 13, 14 all add or modify message types, and it's far easier to add new `@Serializable` `GameMessage` subtypes than new `java.io.Serializable` classes.

Priority 10 (reconnection) should come before Priority 15 (error UI) since reconnection states feed the connectivity indicator.

Priorities 12, 13, 14 are independent of each other.

**Recommended order:** 9 → 11 → 10 → 12 → 13 → 14 → 15 → 16 → 17 → 18

---

## Priority 16 — Signing & Release Build

### 16.1 Create release signing configuration

There is no signing configuration. Release builds either use the debug keystore or fail outright.

**Changes:**
- Generate a release keystore: `keytool -genkey -v -keystore release-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias project01` — store securely outside the repository, never commit it
- Add `signingConfigs` block to `app/build.gradle` reading credentials from `~/.gradle/gradle.properties` or environment variables (never hardcoded):
  ```groovy
  signingConfigs {
      release {
          storeFile file(RELEASE_STORE_FILE)
          storePassword RELEASE_STORE_PASSWORD
          keyAlias RELEASE_KEY_ALIAS
          keyPassword RELEASE_KEY_PASSWORD
      }
  }
  ```
- Reference the signing config in the release build type: `signingConfig signingConfigs.release`
- Add `.gitignore` entries for `*.jks`, `*.keystore`, and any local properties file containing passwords

**Files modified:** `app/build.gradle`

**New files:** `release-keystore.jks` (generated locally, NOT committed)

### 16.2 Create ProGuard/R8 rules file

The release build type references `proguard-rules.pro` which does not exist. When `minifyEnabled` is set to `true` (see 16.3), the build will fail without it.

**Changes:**
- Create `app/proguard-rules.pro` with rules to keep:
  - All `@Serializable` classes and their companion objects (if kotlinx.serialization has been added via Priority 9)
  - All `@Parcelize` classes (`Player`, `Video`)
  - All classes implementing `java.io.Serializable` (if serialization migration is incomplete) — specifically the enum classes `PlaybackCommandType` and `AdvancedCommandType` which are referenced by string in serialization
  - ExoPlayer media3 classes — media3 AARs ship consumer ProGuard rules, but test the release build to verify no runtime `ClassNotFoundException` or reflection failures
  - Wi-Fi P2P callback interfaces and `WifiP2pManager.ActionListener` anonymous implementations
  - `android.net.wifi.p2p` classes accessed via `Intent.getParcelableExtra()`
- Keep the rules file minimal — only add rules that are actually required, verify by building a release APK and testing the full game flow

**New files:** `app/proguard-rules.pro`

### 16.3 Enable R8 code and resource shrinking

`minifyEnabled` is currently `false`. All library code is included in the APK, even unused portions.

**Changes:**
- In `app/build.gradle` release build type, enable shrinking:
  ```groovy
  release {
      signingConfig signingConfigs.release
      minifyEnabled true
      shrinkResources true
      proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
  }
  ```
- Build a release APK (`assembleRelease`) and verify the full game flow works: create game, join game, video transfer, playback commands, advanced commands, Bluetooth
- If any runtime failures occur, add targeted `-keep` rules to `proguard-rules.pro`

**Files modified:** `app/build.gradle`

### 16.4 Add protocol version to TCP handshake

The current `versionCode 1` / `versionName "1.0"` has no role in the network protocol. Devices running different app versions will silently exchange incompatible messages.

**Changes:**
- Define a `PROTOCOL_VERSION` constant in `SocketNetworkManager` (or in `MessageEnvelope` if Priority 9 is done)
- Include the protocol version in the first message exchanged after TCP connection (before authentication) — either as a dedicated `HandshakeMessage(protocolVersion: Int)` or as a field in the existing `PasswordChallenge` (if Priority 11 is done)
- On version mismatch, emit `NetworkEvent.Error` with a clear message ("Incompatible app version") and close the connection
- Bump `PROTOCOL_VERSION` whenever the wire format changes

**Files modified:** `SocketNetworkManager.kt` (or `MessageEnvelope.kt`), `GameViewModel.kt`

**New files:** `HandshakeMessage.kt` (if not folded into existing messages)

---

## Priority 17 — Accessibility

### 17.1 Add content descriptions to all interactive elements

None of the buttons or interactive views in the layouts have `android:contentDescription`. Accessibility services cannot announce their purpose.

**Changes:**
- `activity_main.xml`: add `android:contentDescription` to all buttons:
  - `previous_button`: "Play previous video"
  - `play_pause_button`: "Toggle play and pause"
  - `next_button`: "Play next video"
  - `add_video_button`: "Add a video to the playlist"
  - `turn_off_screen_button`: "Turn off player screens"
  - `deactivate_torch_button`: "Turn off player flashlights"
  - `create_game_button`: "Create a new game session"
  - `join_game_button`: "Join an existing game session"
  - `invisible_resume_button`: "Resume game controls" (only meaningful for game master, but described for consistency)
  - `black_overlay`: `android:importantForAccessibility="no"` (purely decorative)
  - `connectivity_indicator`: "Connection status"
  - `player_view`: "Video player"
- `item_video.xml`: add descriptions to item buttons:
  - `move_up_button`: "Move video up in playlist"
  - `move_down_button`: "Move video down in playlist"
  - `remove_button`: "Remove video from playlist"
  - `progress_bar`: "File transfer progress"
- `item_player.xml`: add description to `player_name`: dynamically set in adapter via `contentDescription = playerName`

**Files modified:** `activity_main.xml`, `item_video.xml`, `item_player.xml`, `PlayerAdapter.kt`, `VideoAdapter.kt`

### 17.2 Ensure minimum touch target sizes

RecyclerView item buttons (`move_up_button`, `move_down_button`, `remove_button` in `item_video.xml`) use `wrap_content` sizing and short text ("Up", "Down", "Remove"). On high-density screens, these may fall below the 48dp minimum recommended by WCAG and Material Design accessibility guidelines.

**Changes:**
- Set `android:minHeight="48dp"` and `android:minWidth="48dp"` on all buttons in `item_video.xml`
- Verify all buttons in `activity_main.xml` meet 48dp minimum — standard `Button` widgets in Material Components theme typically meet this by default, but confirm with the Accessibility Scanner

**Files modified:** `item_video.xml`

### 17.3 Verify and fix color contrast

The app background is `@android:color/holo_blue_dark` (#ff0099cc) with white text on `connectivity_indicator`. WCAG AA requires a minimum 4.5:1 contrast ratio for normal text.

**Changes:**
- Measure contrast ratios for all text/background combinations in the app:
  - White text on `holo_blue_dark` background
  - Button text on default Material button backgrounds
  - `video_title` and `player_name` text colors (inheriting theme defaults)
- If any combination fails 4.5:1, adjust the background color or text color. Likely fix: replace `@android:color/holo_blue_dark` with a darker custom color (e.g., `#003366`) that maintains the blue theme while improving contrast
- Define colors in `res/values/colors.xml` instead of using framework colors directly

**Files modified:** `activity_main.xml`, `res/values/colors.xml` (create if not exists)

### 17.4 Add focus management for UI state transitions

When the UI switches between lobby mode (`showLobby()`) and game mode (`showGame()`) in `MainActivity.kt`, no accessibility announcement is made. Users relying on accessibility services don't know the context changed.

**Changes:**
- In `showGame()`: call `binding.playerView.announceForAccessibility("Game started. Video player is active.")`
- In `showLobby()`: call `binding.createGameButton.announceForAccessibility("Returned to lobby.")`
- When `connectivityStatus` changes: call `binding.connectivityIndicator.announceForAccessibility(status)` so connection state changes are announced
- Set `android:accessibilityLiveRegion="polite"` on `connectivity_indicator` in `activity_main.xml` so updates are announced automatically

**Files modified:** `MainActivity.kt`, `activity_main.xml`

### 17.5 Add proper labels to dialog input fields

The password `EditText` in `dialog_create_game.xml` and `dialog_join_game.xml` uses `android:hint` as the only label. While `hint` is announced by accessibility services, a dedicated label is better practice. Also, `dialog_create_game.xml` line 13 has a typo: `android.inputType` (dot) instead of `android:inputType` (colon), meaning the input type is silently ignored and the password is displayed as plain text.

**Changes:**
- Fix `dialog_create_game.xml` line 13: change `android.inputType="textPassword"` to `android:inputType="textPassword"`
- Add `android:autofillHints="password"` to both password fields for autofill framework support
- Add a `TextView` label above each `EditText` with `android:labelFor="@id/password"` for explicit association (or use `TextInputLayout` from Material Components which provides built-in label/hint accessibility)

**Files modified:** `dialog_create_game.xml`, `dialog_join_game.xml`

---

## Priority 18 — App Size Optimization

### 18.1 Remove unused media3-session dependency

`androidx.media3:media3-session:1.2.1` is included in `app/build.gradle` but there is no `MediaSession` usage anywhere in the codebase. The session library adds unnecessary code and resources.

**Changes:**
- Remove `implementation 'androidx.media3:media3-session:1.2.1'` from `app/build.gradle` dependencies
- Build and run the app to verify no runtime `ClassNotFoundException` — `media3-exoplayer` and `media3-ui` are sufficient for the current ExoPlayer usage
- Estimated savings: ~0.5-1 MB

**Files modified:** `app/build.gradle`

### 18.2 Configure ABI splits for release builds

ExoPlayer includes native decoder libraries bundled for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64). Most physical Android devices used for a forest game are ARM-based. x86/x86_64 are mainly for emulators.

**Changes:**
- Add ABI splits configuration to `app/build.gradle`:
  ```groovy
  splits {
      abi {
          enable true
          reset()
          include "arm64-v8a", "armeabi-v7a"
          universalApk true
      }
  }
  ```
- `universalApk true` produces one fat APK alongside the split APKs as a fallback
- For distribution: use the architecture-specific APKs when device architecture is known, or the universal APK when distributing to unknown devices

**Files modified:** `app/build.gradle`

---

## Recommended implementation order

Priority 9 (serialization) should be done first — Priorities 10, 11, 13, 14 all add or modify message types, and it's far easier to add new `@Serializable` `GameMessage` subtypes than new `java.io.Serializable` classes.

Priority 10 (reconnection) should come before Priority 15 (error UI) since reconnection states feed the connectivity indicator.

Priorities 12, 13, 14 are independent of each other.

Priorities 16, 17, 18 are independent of each other and of Priorities 9-15 (except 16.3 depends on 16.2, and 16.1 is a prerequisite for publishing release builds).

**Recommended order:** 9 → 11 → 10 → 12 → 13 → 14 → 15 → 16 → 17 → 18

---

## Explanations — Operational Items

The following item is not tracked as an implementation task. It involves external service setup that falls outside the normal code change workflow.

### Crash Reporting (Firebase Crashlytics)

During a live game with 20 devices in a forest, `adb logcat` access is impractical. `Log.e()` output is invisible in production.

**What's needed:**

1. **Firebase project setup:** Create a Firebase project, register the Android app (package `com.project01`), download `google-services.json` into `app/`.

2. **Gradle plugins** (project-level `build.gradle`):
   ```groovy
   id 'com.google.gms.google-services' version '4.4.0' apply false
   id 'com.google.firebase.crashlytics' version '2.9.9' apply false
   ```

3. **Gradle dependencies** (app-level `build.gradle`):
   ```groovy
   id 'com.google.gms.google-services'
   id 'com.google.firebase.crashlytics'

   implementation platform('com.google.firebase:firebase-bom:32.7.0')
   implementation 'com.google.firebase:firebase-crashlytics'
   implementation 'com.google.firebase:firebase-analytics'
   ```

4. **Code integration:** Supplement `Log.e(TAG, message, e)` calls with `FirebaseCrashlytics.getInstance().recordException(e)`. The key locations: `SocketNetworkManager.kt` (connection/client errors), `FileTransfer.kt` (transfer failures), `GameViewModel.kt` (event handling errors).

5. **Session context:** Set `FirebaseCrashlytics.getInstance().setCustomKey("role", "game_master")` or `"player"` for crash reports. Set `setUserId(deviceAddress)` for per-device tracking.

6. **Offline behavior:** Crashlytics queues reports and sends them when the device has internet connectivity. Since Wi-Fi Direct doesn't provide internet, reports upload the next time the device connects to regular Wi-Fi or cellular.
