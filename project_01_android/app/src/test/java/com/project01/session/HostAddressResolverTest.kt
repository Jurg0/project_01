package com.project01.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM — no Android networking involved. */
class HostAddressResolverTest {

    @Test
    fun `formatDhcpGateway decodes the little-endian int a hotspot reports`() {
        // 192.168.43.1 little-endian: 192 | 168<<8 | 43<<16 | 1<<24
        val gateway = 192 or (168 shl 8) or (43 shl 16) or (1 shl 24)
        assertEquals("192.168.43.1", HostAddressResolver.formatDhcpGateway(gateway))
    }

    @Test
    fun `formatDhcpGateway returns null for the framework's unknown value`() {
        // 0 means "no DHCP info" — must fall through, not report 0.0.0.0 as the host.
        assertNull(HostAddressResolver.formatDhcpGateway(0))
    }

    @Test
    fun `isUsableHost rejects blank, wildcard and loopback addresses`() {
        assertFalse(HostAddressResolver.isUsableHost(null))
        assertFalse(HostAddressResolver.isUsableHost(""))
        assertFalse(HostAddressResolver.isUsableHost("0.0.0.0"))
        assertFalse("connecting to loopback would dial ourselves",
            HostAddressResolver.isUsableHost("127.0.0.1"))
    }

    @Test
    fun `isUsableHost accepts a real gateway`() {
        assertTrue(HostAddressResolver.isUsableHost("192.168.43.1"))
        assertTrue(HostAddressResolver.isUsableHost("192.168.1.1"))
    }

    @Test
    fun `isUsableHost rejects IPv6 gateways`() {
        // A Wi-Fi link often publishes an IPv6 default route whose gateway is a link-local
        // address. It can't be dialled without a scope id, and trying produced the field
        // ConnectException on one phone while another on the same hotspot connected fine.
        assertFalse(HostAddressResolver.isUsableHost("fe80::1"))
        assertFalse(HostAddressResolver.isUsableHost("fe80::a00:27ff:fe4e:66a1"))
        assertFalse(HostAddressResolver.isUsableHost("::1"))
    }

    @Test
    fun `isIpv4 accepts dotted quads and rejects everything else`() {
        assertTrue(HostAddressResolver.isIpv4("10.0.0.138"))
        assertFalse(HostAddressResolver.isIpv4("192.168.1"))
        assertFalse(HostAddressResolver.isIpv4("192.168.1.999"))
        assertFalse(HostAddressResolver.isIpv4("fe80::1"))
        assertFalse(HostAddressResolver.isIpv4(null))
    }

    @Test
    fun `sameIpv4Subnet keeps gateways on our subnet and rejects strays`() {
        assertTrue(HostAddressResolver.sameIpv4Subnet("192.168.43.57", "192.168.43.1"))
        assertFalse(HostAddressResolver.sameIpv4Subnet("192.168.43.57", "192.168.1.1"))
        assertFalse(HostAddressResolver.sameIpv4Subnet("192.168.43.57", "10.0.0.1"))
    }

    @Test
    fun `sameIpv4Subnet allows a candidate when our own address is unknown`() {
        // Unverifiable is not the same as wrong — still worth dialling.
        assertTrue(HostAddressResolver.sameIpv4Subnet(null, "192.168.43.1"))
    }

    @Test
    fun `accessPointOfSubnet derives the host from our own lease`() {
        // The exact field case: S9 held 10.245.195.150/24 with no IPv4 gateway on the link,
        // so the game master had to be derived as .1 of that subnet.
        assertEquals("10.245.195.1", HostAddressResolver.accessPointOfSubnet("10.245.195.150"))
        assertEquals("192.168.43.1", HostAddressResolver.accessPointOfSubnet("192.168.43.57"))
    }

    @Test
    fun `accessPointOfSubnet returns null when it would point at ourselves or is unusable`() {
        assertNull(HostAddressResolver.accessPointOfSubnet("192.168.43.1"))
        assertNull(HostAddressResolver.accessPointOfSubnet("fe80::1"))
        assertNull(HostAddressResolver.accessPointOfSubnet(null))
    }
}
