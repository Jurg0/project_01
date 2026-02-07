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

### 7.1 Add SocketNetworkManager tests

The most complex and fragile class has zero tests.

**Target test cases:**
- Server start and client connection handshake
- Broadcast delivery to multiple clients
- Client disconnection and cleanup
- Concurrent broadcast during client connect/disconnect
- Shutdown with active connections
- Error emission on connection failure

### 7.2 Expand GameViewModel tests

Currently only `turnOffScreen` and `deactivateTorch` are tested. Add tests for:
- `createGame` / `joinGame` — state transitions and password flow
- `addVideo` / `removeVideo` / `moveVideoUp` / `moveVideoDown` — list manipulation and bounds handling
- Network event handling — all branches of the `when (data)` dispatch
- `playNextVideo` / `onVideoSelected` — playback command emission
- Lifecycle methods — `onPause()`, `onCleared()` resource cleanup

### 7.3 Expand GameRepository tests

Currently only peer list conversion is tested. Add tests for:
- Initialization (channel creation, listener setup)
- `onPause()` / `onResume()` lifecycle behavior
- `shutdown()` cleanup (coroutine cancellation, Wi-Fi P2P cleanup)
- Connection info handling

### 7.4 Add FileTransfer edge case tests

Currently one happy-path integration test. Add tests for:
- Empty file transfer
- Connection failure during transfer
- Progress event accuracy
- Socket closed mid-transfer

### 7.5 Test message serialization round-trips

Zero tests exist for `PlaybackCommand`, `PlaybackState`, `AdvancedCommand`, `PasswordMessage`, `PasswordResponseMessage`, `FileTransferRequest`. Add serialization/deserialization round-trip tests for each, especially important before the JSON migration (Priority 4.1).

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
