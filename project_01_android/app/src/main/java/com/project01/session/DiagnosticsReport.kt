package com.project01.session

/**
 * A snapshot of everything needed to explain a failed join, gathered on the device itself.
 *
 * The fleet is open-ended and the real game master (a Nothing Phone 3) can't be tested
 * beforehand, so the app has to be able to describe its own networking on hardware nobody
 * has run it on. Every field here exists because it would have identified a real field
 * failure in one look:
 *  - [apiLevel] — an API 29 phone silently lost the `dhcpServerAddress` path that an API 30
 *    phone on the same hotspot used successfully;
 *  - [interfaces] / [gatewayCandidates] — one phone's only gateway was IPv6 link-local, which
 *    cannot be dialled, and its subnet had no IPv4 gateway at all;
 *  - [discoveredHost] vs [derivedHost] — shows whether the host answered or we fell back to
 *    guessing;
 *  - [hostReachable] — separates "we picked the wrong address" from "the host isn't serving".
 */
/**
 * Game-master-only state. A host has no "did I find the host?" question — it has "is anyone
 * able to reach me?", and on the real game master (a phone we can't attach a debugger to)
 * this screen is the only way to answer it.
 */
data class HostingState(
    val serverRunning: Boolean,
    val answeringProbes: Boolean,
    val connectedClients: Int,
    val authenticatedClients: Int,
)

data class DiagnosticsReport(
    val deviceModel: String,
    val androidRelease: String,
    val apiLevel: Int,
    val appVersion: String,
    val role: String,
    val connectionState: String,
    val wifiEnabled: Boolean,
    val interfaces: List<String>,
    val gatewayCandidates: List<String>,
    val derivedHost: String?,
    val discoveredHost: String?,
    val hostReachable: String?,
    val playlistSummary: String,
    /** Set on the game master only; the host/probe fields below are meaningless there. */
    val hosting: HostingState? = null,
) {
    /** Plain text, made to be screenshotted or pasted into a message by a tester. */
    fun format(): String = buildString {
        appendLine("— DEVICE —")
        appendLine("$deviceModel · Android $androidRelease (API $apiLevel)")
        appendLine("app $appVersion")
        appendLine()
        appendLine("— SESSION —")
        appendLine("role: $role")
        appendLine("state: $connectionState")
        appendLine("playlist: $playlistSummary")
        appendLine()
        appendLine("— NETWORK —")
        appendLine("wi-fi enabled: $wifiEnabled")
        if (interfaces.isEmpty()) appendLine("interfaces: (none)")
        else {
            appendLine("interfaces:")
            interfaces.forEach { appendLine("  $it") }
        }
        if (gatewayCandidates.isEmpty()) appendLine("gateways: (none advertised)")
        else {
            appendLine("gateways:")
            gatewayCandidates.forEach { appendLine("  $it") }
        }
        appendLine()
        val hostingState = hosting
        if (hostingState != null) {
            // On the host, "can I find the host?" is not a question. What matters is whether
            // players can reach it.
            appendLine("— HOSTING —")
            appendLine("tcp server on $GAME_PORT: ${if (hostingState.serverRunning) "listening" else "NOT RUNNING"}")
            appendLine("answering discovery: ${if (hostingState.answeringProbes) "yes" else "NO"}")
            appendLine("clients connected: ${hostingState.connectedClients}")
            appendLine("players authenticated: ${hostingState.authenticatedClients}")
        } else {
            appendLine("— HOST —")
            appendLine("answered probe: ${discoveredHost ?: "no"}")
            appendLine("derived (fallback): ${derivedHost ?: "none"}")
            appendLine("tcp $GAME_PORT: ${hostReachable ?: "not tested"}")
        }
    }

    companion object {
        const val GAME_PORT = 8888
    }
}
