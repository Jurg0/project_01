package com.project01.session

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM — the report is plain data plus string formatting. */
class DiagnosticsReportTest {

    private fun report(
        apiLevel: Int = 30,
        gateways: List<String> = listOf("192.168.43.1 (default)"),
        derived: String? = "192.168.43.1",
        discovered: String? = "192.168.43.1",
        reachable: String? = "reachable at 192.168.43.1",
    ) = DiagnosticsReport(
        deviceModel = "samsung SM-G960F",
        androidRelease = "10",
        apiLevel = apiLevel,
        appVersion = "1.2.3",
        role = "player",
        connectionState = "CONNECTING",
        wifiEnabled = true,
        interfaces = listOf("wlan0: 10.245.195.150, fe80::7046:92ff:fe6a:cc16"),
        gatewayCandidates = gateways,
        derivedHost = derived,
        discoveredHost = discovered,
        hostReachable = reachable,
        playlistSummary = "3 video(s), 3 on this device",
    )

    @Test
    fun `report names the device and API level`() {
        // The API level alone explained a real failure: an API 29 phone lost a host-resolution
        // path that an API 30 phone on the same hotspot used successfully.
        val text = report(apiLevel = 29).format()
        assertTrue(text.contains("SM-G960F"))
        assertTrue(text.contains("API 29"))
    }

    @Test
    fun `report shows the addresses this device actually has`() {
        val text = report().format()
        assertTrue(text.contains("wlan0: 10.245.195.150"))
    }

    @Test
    fun `report distinguishes an answered probe from a derived fallback`() {
        val answered = report(discovered = "10.245.195.7", derived = "192.168.43.1").format()
        assertTrue(answered.contains("answered probe: 10.245.195.7"))

        val fellBack = report(discovered = null).format()
        assertTrue(fellBack.contains("answered probe: no"))
    }

    @Test
    fun `report surfaces an unreachable host with the reason`() {
        val text = report(reachable = "FAILED to fe80::1 — ConnectException: failed to connect").format()
        assertTrue(text.contains("FAILED to fe80::1"))
        assertTrue(text.contains("ConnectException"))
    }

    @Test
    fun `game master report answers whether players can reach it, not whether it found a host`() {
        // The real game master is a phone we can't attach a debugger to, so this screen is the
        // only window into it. "did I find the host?" is meaningless there.
        val text = report().copy(
            role = "game master",
            hosting = HostingState(
                serverRunning = true,
                answeringProbes = true,
                connectedClients = 3,
                authenticatedClients = 2,
            ),
        ).format()

        assertTrue(text.contains("— HOSTING —"))
        assertTrue(text.contains("listening"))
        assertTrue(text.contains("clients connected: 3"))
        assertTrue(text.contains("players authenticated: 2"))
        assertTrue("must not ask whether it found a host", !text.contains("answered probe"))
    }

    @Test
    fun `game master report shows which videos each player has`() {
        // Field failure: a 200MB video played only on the host while the players sat on a
        // stale frame. The reason was in the roster all along — the players hadn't finished
        // receiving it — but nothing surfaced it, so the host had no way to tell.
        val text = report().copy(
            role = "game master",
            hosting = HostingState(true, true, 2, 2),
            players = listOf("A20e — 2/3 videos, battery 74%", "S9 — 3/3 videos"),
        ).format()

        assertTrue(text.contains("player readiness:"))
        assertTrue(text.contains("A20e — 2/3 videos"))
        assertTrue(text.contains("S9 — 3/3 videos"))
    }

    @Test
    fun `game master report is explicit when no players are in the roster`() {
        val text = report().copy(
            role = "game master",
            hosting = HostingState(true, true, 0, 0),
            players = emptyList(),
        ).format()
        assertTrue(text.contains("no players in the roster"))
    }

    @Test
    fun `game master report shouts when it is not actually serving`() {
        val text = report().copy(
            role = "game master",
            hosting = HostingState(
                serverRunning = false,
                answeringProbes = false,
                connectedClients = 0,
                authenticatedClients = 0,
            ),
        ).format()

        assertTrue(text.contains("NOT RUNNING"))
        assertTrue(text.contains("answering discovery: NO"))
    }

    @Test
    fun `report is explicit when the link advertises no gateway`() {
        // The exact field case: only a connected route, no IPv4 gateway anywhere.
        val text = report(gateways = emptyList()).format()
        assertTrue(text.contains("(none advertised)"))
    }
}
