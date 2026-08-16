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
     * Whether [address] is a usable host address: a real IPv4 address, not a wildcard or
     * loopback entry (which would dial ourselves).
     *
     * IPv4-only on purpose. A Wi-Fi link often also carries IPv6 routes, and an IPv6 default
     * route's gateway is typically a link-local `fe80::…` address that cannot be dialled
     * without a scope id — connecting to it fails with exactly the ConnectException seen in
     * the field. Which routes a link publishes varies by phone and hotspot, so one device
     * could connect while another, on the same hotspot, could not.
     */
    fun isUsableHost(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        if (address == "0.0.0.0" || address.startsWith("127.")) return false
        return isIpv4(address)
    }

    /** Dotted-quad check — rejects IPv6 literals (which contain ':') and malformed input. */
    fun isIpv4(address: String?): Boolean {
        val parts = address?.split('.') ?: return false
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all { it.isDigit() } && (part.toIntOrNull() ?: -1) in 0..255
        }
    }

    /**
     * Whether [candidate] plausibly sits on the same /24 as [own] — the layout every phone
     * hotspot uses (clients on 192.168.x.N, the access point on 192.168.x.1). A gateway
     * outside our own subnet cannot be the game master, so we skip it and try the next
     * source rather than dialling an address that can never answer.
     *
     * Returns true when [own] is unknown: an unverifiable candidate is still worth trying.
     */
    fun sameIpv4Subnet(own: String?, candidate: String?): Boolean {
        if (own == null) return true
        if (!isIpv4(own) || !isIpv4(candidate)) return false
        return own.split('.').take(3) == candidate!!.split('.').take(3)
    }

    /**
     * The access point of [own]'s /24 — host .1 — or null if [own] isn't a usable IPv4.
     *
     * Last-resort fallback for when the link advertises no IPv4 gateway at all. Field case:
     * a Samsung S9 (API 29, so no `dhcpServerAddress`) on the game master's hotspot had only
     * a connected route (`10.245.195.0/24`, no gateway) plus an IPv6 default route, so no
     * source could name the host. Because the game master *is* the access point and phone
     * hotspots address themselves as .1 of the /24 they hand out, deriving it from our own
     * lease is reliable here. It is a convention, not a lookup — hence the last position.
     */
    fun accessPointOfSubnet(own: String?): String? {
        if (!isIpv4(own)) return null
        val octets = own!!.split('.')
        if (octets[3] == "1") return null   // we are the .1 — don't dial ourselves
        return "${octets[0]}.${octets[1]}.${octets[2]}.1"
    }
}
