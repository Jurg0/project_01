package com.project01.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.project01.session.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.IOException

class GameRepository(
    private val application: Application,
    val gameSync: GameSync = GameSync(SocketNetworkManager()),
    val fileTransfer: FileTransfer = FileTransfer(),
    val snapshotManager: SnapshotManager = SnapshotManager(
        java.io.File(application.filesDir, "game_state_snapshot.json")
    ),
    val playlistStore: PlaylistStore = PlaylistStore(
        java.io.File(application.filesDir, "playlists")
    ),
    val preparedGameStore: PreparedGameStore = PreparedGameStore(
        java.io.File(application.filesDir, "prepared")
    ),
    val hostDiscovery: HostDiscovery = HostDiscovery(),
) {

    private val _players = MutableLiveData<List<Player>>()
    val players: LiveData<List<Player>> = _players

    // Synchronous mirror of the roster, for the same reason as videosMirror below: every
    // roster update is a read-modify-write (add a player, rename one, attach its status) and
    // LiveData.value lags a postValue. Two players authenticating close together both read
    // the same stale list and the second write erases the first — the game master then shows
    // one player while two are connected. Updated in updatePlayers alongside the LiveData.
    @Volatile
    private var playersMirror: List<Player> = emptyList()
    val currentPlayers: List<Player> get() = playersMirror

    private val _videos = MutableLiveData<List<Video>>()
    val videos: LiveData<List<Video>> = _videos

    // Synchronous mirror of the current playlist. LiveData.value lags a postValue
    // (postValue defers setValue to the main thread), so a burst of file-transfer
    // Success events that each read `videos.value`, swap one entry, and post back
    // would build on a stale list and clobber each other. The mirror is read/written
    // synchronously, keeping the per-file URI swap lossless. Updated in restoreVideos
    // alongside the LiveData.
    @Volatile
    private var videosMirror: List<Video> = emptyList()
    val currentVideos: List<Video> get() = videosMirror

    private val _isGameStarted = MutableLiveData<Boolean>()
    val isGameStarted: LiveData<Boolean> = _isGameStarted

    private val _toastMessage = MutableLiveData<String>()
    val toastMessage: LiveData<String> = _toastMessage

    private val _fileTransferEvent = MutableLiveData<FileTransferEvent>()
    val fileTransferEvent: LiveData<FileTransferEvent> = _fileTransferEvent

    // Invoked on EVERY FileTransfer Success — drives the player-side content:// →
    // file:// playlist swap. Deliberately NOT routed through fileTransferEvent above:
    // that LiveData is fed by postValue, which coalesces bursts and silently drops
    // intermediate values. During a multi-video pull the flood of Progress events
    // overwrote the Success values, so most videos never got their URI swapped and
    // only the first video ever played on players (field bug). Set by GameViewModel.
    var onFileReceived: ((String) -> Unit)? = null

    private val _gameSyncEvent = MutableLiveData<NetworkEvent>()
    val gameSyncEvent: LiveData<NetworkEvent> = _gameSyncEvent

    private val wifiManager: android.net.wifi.WifiManager by lazy {
        application.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
    }
    private val connectivityManager: ConnectivityManager by lazy {
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    fun openWifiSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
    }

    /**
     * The game host's IP, or null if this device isn't on the game's network.
     *
     * FALLBACK ONLY — [HostDiscovery] asking the host directly is the primary path, because
     * every derivation below encodes an assumption about how the hosting phone configures its
     * hotspot, and that varies by device and Android version. Sources are tried in order
     * because the fleet spans many Android versions (minSdk 24):
     *  1. `dhcpServerAddress` — API 30+ only (the A20e is exactly API 30); the most precise,
     *     since for a hotspot the DHCP server *is* the access point.
     *  2. the default route's gateway — available on every supported version.
     *  3. the legacy `DhcpInfo.gateway` int — deprecated in API 31 but still functional, and
     *     the last resort for the oldest phones.
     * The chosen source is logged so a field failure can be diagnosed from one log line.
     */
    fun resolveHostAddress(): String? {
        val wifi = wifiNetwork()
        val linkProperties = try {
            wifi?.let { connectivityManager.getLinkProperties(it) }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getLinkProperties failed", e)
            null
        }

        // Our own IPv4 on the Wi-Fi link, used to sanity-check candidates: the hotspot's
        // access point is always on our subnet. Logged because it makes a field log
        // self-explanatory — "own ip = 192.168.43.57" tells you the host must be 192.168.43.x.
        val ownIpv4 = linkProperties?.linkAddresses
            ?.mapNotNull { (it.address as? java.net.Inet4Address)?.hostAddress }
            ?.firstOrNull()
        android.util.Log.d(TAG, "own ip = $ownIpv4")

        fun accept(candidate: String?, source: String): String? {
            if (!HostAddressResolver.isUsableHost(candidate)) return null
            if (!HostAddressResolver.sameIpv4Subnet(ownIpv4, candidate)) {
                android.util.Log.w(TAG, "ignoring $candidate ($source) — not on our subnet ($ownIpv4)")
                return null
            }
            android.util.Log.d(TAG, "host = $candidate ($source)")
            return candidate
        }

        if (linkProperties != null && android.os.Build.VERSION.SDK_INT >= 30) {
            accept(linkProperties.dhcpServerAddress?.hostAddress, "dhcpServerAddress")?.let { return it }
        }

        // Prefer the default route, but accept any route that names a gateway — a hotspot
        // with no upstream internet doesn't always publish a default route. IPv4 only: an
        // IPv6 default route's gateway is a link-local address we cannot dial.
        linkProperties?.routes
            ?.filter { it.gateway is java.net.Inet4Address }
            ?.sortedByDescending { it.isDefaultRoute }
            ?.firstNotNullOfOrNull { accept(it.gateway?.hostAddress, "route gateway") }
            ?.let { return it }

        @Suppress("DEPRECATION")
        val dhcpGateway = try {
            HostAddressResolver.formatDhcpGateway(wifiManager.dhcpInfo?.gateway ?: 0)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "dhcpInfo failed", e)
            null
        }
        accept(dhcpGateway, "dhcpInfo")?.let { return it }

        // Nothing on the link names an IPv4 gateway (seen in the field: a connected route with
        // no gateway plus an IPv6 default route). The game master is the access point, so
        // derive it from our own lease as .1 of our /24.
        accept(HostAddressResolver.accessPointOfSubnet(ownIpv4), "subnet .1 convention")?.let { return it }

        android.util.Log.w(TAG, "could not resolve a host address — not connected to the game's Wi-Fi?")
        return null
    }

    /**
     * The Wi-Fi network, which is NOT necessarily the *active* (default) one.
     *
     * The game's hotspot has no upstream internet, so Android flags it "no internet" and, on
     * a phone with mobile data switched on, keeps **cellular** as the default network. Asking
     * for `activeNetwork` there returns cellular, whose routes yield a carrier gateway — we
     * then dialled that on port 8888 and got a ConnectException, while a phone with no SIM
     * (Wi-Fi is its default) connected fine. Always pick the Wi-Fi transport explicitly.
     */
    private fun wifiNetwork(): android.net.Network? = try {
        @Suppress("DEPRECATION")
        connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "could not enumerate networks", e)
        null
    }

    /**
     * Route this process's sockets over the game's Wi-Fi network (or restore the system
     * default when [bound] is false).
     *
     * Resolving the right address is not enough: when the hotspot isn't the default network,
     * an unbound socket can still be sent out over cellular and fail to reach the host.
     * Binding pins the game's TCP traffic to the Wi-Fi network for the session. The game
     * needs no internet, so losing the default route while bound costs nothing — but it must
     * be released at session end, which the callers do.
     */
    fun setGameNetworkBound(bound: Boolean) {
        try {
            val target = if (bound) wifiNetwork() else null
            if (bound && target == null) {
                android.util.Log.w(TAG, "no Wi-Fi network to bind to")
                return
            }
            connectivityManager.bindProcessToNetwork(target)
            android.util.Log.d(TAG, if (bound) "bound process to Wi-Fi network" else "released network binding")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        observeGameSyncEvents()
        observeFileTransferEvents()
    }

    private fun observeGameSyncEvents() {
        gameSync.events.onEach { event ->
            _gameSyncEvent.postValue(event)
        }.launchIn(coroutineScope)
    }

    private fun observeFileTransferEvents() {
        fileTransfer.events.onEach { event ->
            // Drive the lossless swap first so no Success is ever dropped, THEN post
            // to the coalescing LiveData that only feeds cosmetic progress/toasts.
            if (event is FileTransferEvent.Success) onFileReceived?.invoke(event.fileName)
            _fileTransferEvent.postValue(event)
        }.launchIn(coroutineScope)
    }

    fun setGameStarted(started: Boolean) {
        _isGameStarted.postValue(started)
    }

    fun updatePlayers(players: List<Player>) {
        playersMirror = players
        _players.postValue(players)
    }

    fun restoreVideos(videos: List<Video>) {
        videosMirror = videos
        _videos.postValue(videos)
    }

    fun showToast(message: String) {
        _toastMessage.postValue(message)
    }

    fun getFileName(uri: Uri): String? {
        var name: String? = null
        application.contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    name = it.getString(displayNameIndex)
                }
            }
        }
        return name
    }

    suspend fun findFreePort(): Int = withContext(Dispatchers.IO) {
        try {
            val socket = java.net.ServerSocket(0)
            val port = socket.localPort
            socket.close()
            port
        } catch (e: IOException) {
            android.util.Log.e("GameRepository", "Failed to find free port", e)
            -1
        }
    }

    /** Game-master side: answer LAN discovery probes so joiners can find us. */
    fun setDiscoveryResponder(running: Boolean) {
        if (running) hostDiscovery.startResponding(coroutineScope) else hostDiscovery.stopResponding()
    }

    /** Player side: ask the game master for its address. Null if nothing answered. */
    suspend fun discoverHost(): String? = hostDiscovery.findHost()

    /**
     * Gather a self-describing network report. Every step is individually guarded: this runs
     * on phones we have never tested (the real game master is a device we can't obtain), so a
     * diagnostics screen that crashes is worse than useless.
     */
    suspend fun collectDiagnostics(
        role: String,
        connectionState: String,
        playlistSummary: String,
        hosting: HostingState?,
        players: List<String> = emptyList(),
    ): DiagnosticsReport = withContext(Dispatchers.IO) {
        val interfaces = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .map { nif ->
                    val addresses = nif.inetAddresses.toList().joinToString(", ") { addr ->
                        addr.hostAddress ?: "?"
                    }
                    "${nif.name}: ${addresses.ifEmpty { "(no address)" }}"
                }
        } catch (e: Exception) {
            listOf("error: ${e.javaClass.simpleName}")
        }

        val gateways = try {
            val lp = wifiNetwork()?.let { connectivityManager.getLinkProperties(it) }
            buildList {
                lp?.routes?.forEach { route ->
                    val gw = route.gateway?.hostAddress
                    if (gw != null) add("$gw${if (route.isDefaultRoute) " (default)" else ""}")
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    lp?.dhcpServerAddress?.hostAddress?.let { add("$it (dhcp server)") }
                }
            }
        } catch (e: Exception) {
            listOf("error: ${e.javaClass.simpleName}")
        }

        // The host doesn't look for itself: probing and dialling would only time out and make
        // the report slow, and its own section reports what actually matters instead.
        val isHost = hosting != null
        val derived = if (isHost) null else try { resolveHostAddress() } catch (e: Exception) { null }
        val discovered = if (isHost) null else try { hostDiscovery.findHost() } catch (e: Exception) { null }

        val target = discovered ?: derived
        val reachability = when {
            isHost -> null
            target == null -> "no address to test"
            else -> try {
                java.net.Socket().use { socket ->
                    socket.connect(
                        java.net.InetSocketAddress(target, DiagnosticsReport.GAME_PORT),
                        REACHABILITY_TIMEOUT_MS,
                    )
                    "reachable at $target"
                }
            } catch (e: Exception) {
                "FAILED to $target — ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        DiagnosticsReport(
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            androidRelease = android.os.Build.VERSION.RELEASE ?: "?",
            apiLevel = android.os.Build.VERSION.SDK_INT,
            appVersion = try {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "?"
            } catch (e: Exception) { "?" },
            role = role,
            connectionState = connectionState,
            wifiEnabled = try { isWifiEnabled() } catch (e: Exception) { false },
            interfaces = interfaces,
            gatewayCandidates = gateways,
            derivedHost = derived,
            discoveredHost = discovered,
            hostReachable = reachability,
            playlistSummary = playlistSummary,
            hosting = hosting,
            players = players,
        )
    }

    fun shutdown() {
        coroutineScope.cancel()
        hostDiscovery.stopResponding()
        gameSync.shutdown()
        fileTransfer.shutdown()
    }

    companion object {
        /** Logcat tag for host-address resolution: `adb logcat -s GameNet`. */
        private const val TAG = "GameNet"
        private const val REACHABILITY_TIMEOUT_MS = 3_000
    }
}


