package com.project01.session

/**
 * Pure helpers for working out the game host's IP address.
 *
 * The game master hosts the Wi-Fi hotspot everyone is already connected to, so the GM is
 * the network's gateway. [GameRepository.resolveHostAddress] walks three sources in order
 * (DHCP server address on API 30+, the default route's gateway, then the legacy DhcpInfo)
 * and the Android-free parts live here so they can be unit-tested on the JVM.
 */
object HostAddressResolver {

    /**
     * Format the little-endian IPv4 integer from `WifiManager.getDhcpInfo().gateway` as a
     * dotted quad. Returns null for 0 (the framework's "unknown" value) so callers can fall
     * through instead of reporting "0.0.0.0" as the host.
     */
    fun formatDhcpGateway(gateway: Int): String? {
        if (gateway == 0) return null
        return "${gateway and 0xff}.${gateway shr 8 and 0xff}.${gateway shr 16 and 0xff}.${gateway shr 24 and 0xff}"
    }

    /**
     * Whether [address] is a usable host address. Guards against the framework handing back
     * a wildcard/loopback entry, which would produce a socket connection to ourselves.
     */
    fun isUsableHost(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return address != "0.0.0.0" && !address.startsWith("127.")
    }
}
